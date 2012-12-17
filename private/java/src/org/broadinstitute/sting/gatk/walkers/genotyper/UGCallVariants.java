/*
 * Copyright (c) 2010, The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.genotyper;

import org.broadinstitute.sting.commandline.ArgumentCollection;
import org.broadinstitute.sting.commandline.Input;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.commandline.RodBinding;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.utils.SampleUtils;
import org.broadinstitute.sting.utils.codecs.vcf.*;
import org.broadinstitute.sting.utils.variantcontext.writer.VariantContextWriter;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.variantcontext.*;

import java.util.*;

/**
 * Uses the UG engine to call variants based off of VCFs annotated with GLs (or PLs).
 * Absolutely not supported or recommended for public use.
 * Run this as you would the UnifiedGenotyper, except that instead of '-I reads' it expects any number
 * of GL/PL-annotated VCFs bound to a name starting with 'variant'.
 */
public class UGCallVariants extends RodWalker<List<VariantContext>, Integer> {

    @ArgumentCollection
    private UnifiedArgumentCollection UAC = new UnifiedArgumentCollection();

    @Input(fullName="variant", shortName = "V", doc="Input VCF file", required=true)
    public List<RodBinding<VariantContext>> variants;

    // control the output
    @Output(doc="File to which variants should be written",required=true)
    protected VariantContextWriter writer = null;

    // the calculation arguments
    private UnifiedGenotyperEngine UG_engine = null;

    // variant track names
    private Set<String> trackNames = new HashSet<String>();

    public void initialize() {
        for ( RodBinding<VariantContext> rb : variants )
            trackNames.add(rb.getName());
        Set<String> samples = SampleUtils.getSampleListWithVCFHeader(getToolkit(), trackNames);

        UG_engine = new UnifiedGenotyperEngine(getToolkit(), UAC, logger, null, null, samples, VariantContextUtils.DEFAULT_PLOIDY);

        Set<VCFHeaderLine> headerInfo = new HashSet<VCFHeaderLine>();

        // If relevant, add in the alleles ROD's header fields (first, so that they can be overriden by the fields we manually add below):
        if (UAC.GenotypingMode == GenotypeLikelihoodsCalculationModel.GENOTYPING_MODE.GENOTYPE_GIVEN_ALLELES) {
            LinkedList<String> allelesRods = new LinkedList<String>();
            allelesRods.add(UAC.alleles.getName());
            headerInfo.addAll(VCFUtils.getHeaderFields(getToolkit(), allelesRods));
        }

        headerInfo.addAll(UnifiedGenotyper.getHeaderInfo(UAC, null, null));

        // initialize the header
        writer.writeHeader(new VCFHeader(headerInfo, samples));
    }

    public List<VariantContext> map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        if ( tracker == null )
            return null;

        List<VariantContext> retVC = new LinkedList<VariantContext>();

        List<RefMetaDataTracker> useTrackers = new LinkedList<RefMetaDataTracker>();
        // Allow for multiple records in variants, even at same locus:
        if ( UAC.GenotypingMode == GenotypeLikelihoodsCalculationModel.GENOTYPING_MODE.GENOTYPE_GIVEN_ALLELES ) {
            for (VariantContext vc : tracker.getValues(variants, context.getLocation()))
                useTrackers.add(new MatchFirstLocRefAltRefMetaDataTracker(tracker, vc));
        }
        else
            useTrackers.add(tracker);

        for (RefMetaDataTracker t : useTrackers) {
            List<VariantContext> VCs = t.getValues(variants, context.getLocation());

            VariantContext mergedVC = mergeVCsWithGLs(VCs, t, context);
            if (mergedVC == null)
                continue;

            VariantContext mergedVCwithGT = UG_engine.calculateGenotypes(t, ref, context, mergedVC);

            if (mergedVCwithGT == null)
                continue;

            // Add the filters and attributes from the mergedVC first (so they can be overriden as necessary by mergedVCwithGT):
            VariantContextBuilder vcb = new VariantContextBuilder(mergedVCwithGT);
            vcb.log10PError(mergedVC.getLog10PError());

            Set<String> filters = new HashSet<String>();
            Map<String, Object> attributes = new HashMap<String, Object>();

            filters.addAll(mergedVC.getFilters());
            attributes.putAll(mergedVC.getAttributes());

            // Only want filters from the original VCFs here, but not any new ones (e.g., LowQual):
            /*
            filters.addAll(mergedVCwithGT.getFilters());
            */
            attributes.putAll(mergedVCwithGT.getAttributes());

            retVC.add(vcb.filters(filters).attributes(attributes).make());
        }

        return retVC;
    }

    public Integer reduceInit() { return 0; }

    public Integer reduce(List<VariantContext> value, Integer sum) {
        if ( value == null )
            return sum;

        try {
            for (VariantContext vc : value) {
                VariantContextBuilder builder = new VariantContextBuilder(vc);
                VariantContextUtils.calculateChromosomeCounts(builder, true);
                writer.add(builder.make());
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }

        return sum + value.size();
    }

    public void onTraversalDone(Integer result) {
        logger.info(String.format("Visited variants: %d", result));
    }

    private VariantContext mergeVCsWithGLs(List<VariantContext> VCs, RefMetaDataTracker tracker, AlignmentContext context) {
        // we can't use the VCUtils classes because our VCs can all be no-calls
        if ( VCs.size() == 0 )
            return null;

        VariantContext variantVC = null;
        GenotypesContext genotypes = GenotypesContext.create();
        for ( VariantContext vc : VCs ) {
            if ( variantVC == null && vc.isVariant() )
                variantVC = vc;
            genotypes.addAll(getGenotypesWithGLs(vc.getGenotypes()));
        }

        if ( variantVC == null ) {
            VariantContext vc = VCs.get(0);
            throw new UserException("There is no ALT allele in any of the VCF records passed in at " + vc.getChr() + ":" + vc.getStart());
        }
        VariantContextBuilder vcb = new VariantContextBuilder(variantVC);

        Set<String> filters = new HashSet<String>();
        Map<String, Object> attributes = new HashMap<String, Object>();

        // If relevant, add the attributes from the alleles ROD first (so they can be overriden as necessary by variantVC below):
        if (UAC.GenotypingMode == GenotypeLikelihoodsCalculationModel.GENOTYPING_MODE.GENOTYPE_GIVEN_ALLELES) {
            List<VariantContext> allelesVCs = tracker.getValues(UAC.alleles, context.getLocation());
            for (VariantContext alleleVC : allelesVCs) {
                filters.addAll(alleleVC.getFilters());
                attributes.putAll(alleleVC.getAttributes());

                // Use the existing value as the quality score for the merged variant:
                // TODO: currently, VariantContextUtils.simpleMerge "take the QUAL of the first VC with a non-MISSING qual for the combined value"
                // TODO: we probably would want to take the minimum:
                vcb.log10PError(alleleVC.getLog10PError());
            }
        }
        filters.addAll(variantVC.getFilters());
        attributes.putAll(variantVC.getAttributes());

        vcb.filters(filters);
        vcb.attributes(attributes);

        return vcb.source("VCwithGLs").genotypes(genotypes).make();
    }

    private static GenotypesContext getGenotypesWithGLs(GenotypesContext genotypes) {
        GenotypesContext genotypesWithGLs = GenotypesContext.create(genotypes.size());
        for ( final Genotype g : genotypes ) {
            if ( g.hasLikelihoods() && g.getLikelihoods().getAsVector() != null )
                genotypesWithGLs.add(g);
        }
        return genotypesWithGLs;
    }
}