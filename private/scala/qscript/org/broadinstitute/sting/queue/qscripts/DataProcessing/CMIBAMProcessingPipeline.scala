/**
 * Created with IntelliJ IDEA.
 * User: carneiro
 * Date: 9/25/12
 * Time: 12:04 PM
 */

package org.broadinstitute.sting.queue.qscripts.DataProcessing

import us.countmein.queueext._
import org.broadinstitute.sting.queue.extensions.gatk._
import org.broadinstitute.sting.gatk.walkers.indels.IndelRealigner.ConsensusDeterminationModel
import org.broadinstitute.sting.utils.baq.BAQ.CalculationMode

import net.sf.samtools.SAMFileHeader.SortOrder

import org.broadinstitute.sting.queue.function.ListWriterFunction
import org.broadinstitute.sting.commandline.Hidden
import org.broadinstitute.sting.utils.NGSPlatform
import collection.mutable
import org.broadinstitute.sting.queue.extensions.picard._
import io.Source
import java.util.Date
import org.broadinstitute.sting.gatk.walkers.genotyper.{UnifiedGenotyperEngine, GenotypeLikelihoodsCalculationModel}
import org.broadinstitute.sting.utils.exceptions.UserException
import java.io.File

class CMIBAMProcessingPipeline extends CmiScript {
  qscript =>

  /** ***************************************************************************
    * Required Parameters
    * ***************************************************************************/

  @Input(doc = "a table with all the necessary information to process the data", fullName = "metadata", shortName = "m", required = false, exclusiveOf = "individual")
  var metaData: File = _

  @Argument(exclusiveOf = "metaData", required = false)
  var individual: String = _

  //  @Input(doc="a table with all the necessary information to process the data", fullName="metadata", shortName="m", required=true)
  //  var metaData: File = _

  /** ******************************************************************************
    * Additional Parameters that the pipeline should have pre-defined in the image
    * ******************************************************************************/

  @Input(doc="argument that allows Queue to see and download files", fullName="file1", required=false)
  var file1: Seq[File] = Nil
  @Input(doc="even more files that should be downloaded", fullName="file2", required=false)
  var file2: Seq[File] = Nil

  @Input(doc = "Reference fasta file", fullName = "reference", shortName = "R", required = false)
  var reference: File = new File("/refdata/human_g1k_v37_decoy.fasta")

  @Input(doc = "DBSNP or known callset to use (must be in VCF format)", fullName = "dbsnp", shortName = "D", required = false)
  var dbSNP: Seq[File] = Seq(new File("/refdata/dbsnp_135.b37.vcf"))

  @Input(doc = "The path to the binary of bwa (usually BAM files have already been mapped - but if you want to remap this is the option)", fullName = "path_to_bwa", shortName = "bwa", required = false)
  var bwaPath: File = "/opt/bwa/bin/bwa"

  @Input(doc = "extra VCF files to use as reference indels for Indel Realignment", fullName = "extra_indels", shortName = "indels", required = false)
  var indelSites: Seq[File] = Seq()

  @Input(doc = "Interval file with targets used in exome capture (used for QC metrics)", fullName = "targets", shortName = "targets", required = false)
  var targets: File = new File("/refdata/whole_exome_agilent_1.1_refseq_plus_3_boosters.Homo_sapiens_b37_decoy.targets.interval_list")

  @Input(doc = "Interval file with baits used in exome capture (used for QC metrics)", fullName = "baits", shortName = "baits", required = false)
  var baits: File = new File("/refdata/whole_exome_agilent_1.1_refseq_plus_3_boosters.Homo_sapiens_b37_decoy.baits.interval_list")

  @Input(doc = "File containing known sites and alleles to evaluate single-sample calls", fullName = "knownCalls", shortName = "knownCalls", required = false)
  var knownSites: File = new File("/refdata/ALL.wgs.phase1_release_v3.20101123.snps_indels_sv.sites.vcf.gz")

  @Argument(doc = "Command line to invoke ContEst", fullName = "contestBin", shortName = "contestBin", required = false)
  val contestBin = "java -Djava.io.tmpdir=/local/tmp -Xmx1g -jar /opt/ContEst/ContEst-1.0.2.jar -S /opt/ContEst/ContaminationPipeline.scala "

  @Input(doc = "Interval list of SNP6 sites used by ContEst", fullName = "contestArrayIntervals", shortName = "contestArrayIntervals", required = false)
  val contestArrayIntervals: File = new File("/refdata/SNP6.hg19.interval_list")

  @Input(doc = "Interval list of SNP6 site HapMap Population Frequencies", fullName = "contestPopFrequencies", shortName = "contestPopFrequencies", required = false)
  val contestPopFrequencies:  File = new File("/refdata/hg19_population_stratified_af_hapmap_3.3.fixed.vcf")


  /** **************************************************************************
    * Output files, to be passed in by messaging service
    * ***************************************************************************/

  //@Output(doc = "Processed unreduced normal BAM", fullName = "unreducedNormalBAM", shortName = "unb", required = false) // Using full name, so json field is mixed case "unfilteredVcf" or "uv"
  //var unreducedNormalBAM: File = _

  //@Output(doc = "Processed reduced normal BAMs", fullName = "reducedNormalBAM", shortName = "rnb", required = false)
  //var reducedNormalBAM: File = _

  //@Output(doc = "Processed unreduced normal BAM Index", fullName = "unreducedNormalBAMIndex", shortName = "unbi", required = false) // Using full name, so json field is mixed case "unfilteredVcf" or "uv"
  //var unreducedNormalBAMIndex: File = _

  //@Output(doc = "Processed reduced normal BAM Index", fullName = "reducedNormalBAMIndex", shortName = "rnbi", required = false)
  //var reducedNormalBAMIndex: File = _

  //@Output(doc = "Processed unreduced tumor BAM", fullName = "unreducedTumorBAM", shortName = "utb", required = false) // Using full name, so json field is mixed case "unfilteredVcf" or "uv"
  //var unreducedTumorBAM: File = _

  //@Output(doc = "Processed reduced tumor BAM", fullName = "reducedTumorBAM", shortName = "rtb", required = false)
  //var reducedTumorBAM: File = _

  //@Output(doc = "Processed unreduced tumor BAM Index", fullName = "unreducedTumorBAMIndex", shortName = "utbi", required = false) // Using full name, so json field is mixed case "unfilteredVcf" or "uv"
  //var unreducedTumorBAMIndex: File = _

  //@Output(doc = "Processed reduced tumor BAM Index", fullName = "reducedTumorBAMIndex", shortName = "rtbi", required = false)
  //var reducedTumorBAMIndex: File = _

  //// picard metrics outputs
  //@Output(doc = "Tumor HS Metrics", fullName = "tumorHSMetrics", shortName = "thsm", required = false)
  //var tumorHSMetrics: File = _

  //// picard metrics outputs
  //@Output(doc = "Tumor GC Metrics", fullName = "tumorGCMetrics", shortName = "tgcm", required = false)
  //var tumorGCMetrics: File = _

  //// picard metrics outputs
  //@Output(doc = "Tumor alignment Metrics", fullName = "tumorAlignmentMetrics", shortName = "tam", required = false)
  //var tumorAlignmentMetrics: File = _

  //@Output(doc = "Tumor insert size Metrics", fullName = "tumorInsertMetrics", shortName = "tim", required = false)
  //var tumorInsertSizeMetrics: File = _

  //@Output(doc = "Tumor quality by cycle Metrics", fullName = "tumorQualCMetrics", shortName = "tqcm", required = false)
  //var tumorQualityByCycleMetrics: File = _

  //@Output(doc = "Tumor alignment Metrics", fullName = "tumorQualDMetrics", shortName = "tqdm", required = false)
  //var tumorQualityDistributionMetrics: File = _

  //// picard metrics outputs
  //@Output(doc = "Normal HS Metrics", fullName = "normalHSMetrics", shortName = "nhsm", required = false)
  //var normalHSMetrics: File = _

  //// picard metrics outputs
  //@Output(doc = "Normal GC Metrics", fullName = "normalGCMetrics", shortName = "ngcm", required = false)
  //var normalGCMetrics: File = _

  //// picard metrics outputs
  //@Output(doc = "Normal alignment Metrics", fullName = "normalAlignmentMetrics", shortName = "nam", required = false)
  //var normalAlignmentMetrics: File = _

  //@Output(doc = "Normal insert size Metrics", fullName = "normalInsertMetrics", shortName = "nim", required = false)
  //var normalInsertSizeMetrics: File = _

  //@Output(doc = "Normal quality by cycle Metrics", fullName = "normalQualCMetrics", shortName = "nqcm", required = false)
  //var normalQualityByCycleMetrics: File = _

  //@Output(doc = "Normal alignment Metrics", fullName = "normalQualDMetrics", shortName = "nqdm", required = false)
  //var normalQualityDistributionMetrics: File = _


  //@Output(doc = "Normal duplicate metrics", fullName = "normalDuplicateMetrics", shortName = "ndm", required = false)
  //var normalDuplicateMetrics: File = _

  //@Output(doc = "Tumos duplicate metrics", fullName = "tumorDuplicateMetrics", shortName = "tdm", required = false)
  //var tumorDuplicateMetrics: File = _

  //// in case single sample calls are requested
  //@Output(doc = "Processed single sample VCF", fullName = "singleSampleVCF", shortName = "ssvcf", required = false) // Using full name, so json field is mixed case "unfilteredVcf" or "uv"
  //var singleSampleVCF: File = _

  //@Output(doc = "Processed single sample VCF index", fullName = "singleSampleVCFIndex", shortName = "ssvcfi", required = false)
  //var singleSampleVCFIndex: File = _

  /** **************************************************************************
    * Hidden Parameters
    * ***************************************************************************/
  @Hidden
  @Argument(doc = "Use BWASW instead of BWA aln", fullName = "use_bwa_sw", shortName = "bwasw", required = false)
  var useBWAsw: Boolean = false

  @Hidden
  @Argument(doc = "Collect Picard QC metrics", fullName = "skipQC", shortName = "skipqc", required = false)
  var skipQCMetrics: Boolean = false

  @Hidden
  @Argument(doc = "Number of threads jobs should use when possible", fullName = "numThreads", shortName = "nct", required = false)
  var numThreads: Int = 4 // HOTFIX m1.large has 4 cores?

  @Hidden
  @Argument(doc = "Default memory limit per job", fullName = "mem_limit", shortName = "mem", required = false)
  var memLimit: Int = 1

  @Hidden
  @Argument(doc = "How many ways to scatter/gather", fullName = "scatter_gather", shortName = "sg", required = false)
  var nContigs: Int = 0

  @Hidden
  @Argument(doc = "Define the default platform for Count Covariates -- useful for techdev purposes only.", fullName = "default_platform", shortName = "dp", required = false)
  var defaultPlatform: String = ""

  @Hidden
  @Argument(doc = "Run the pipeline in test mode only", fullName = "test_mode", shortName = "test", required = false)
  var testMode: Boolean = false

  @Hidden
  @Argument(doc = "Run the pipeline in quick mode only", fullName = "quick", shortName = "quick", required = false)
  var quick: Boolean = false

  @Hidden
  @Argument(doc = "Base path for FASTQs", fullName = "baseFastqPath", shortName = "baseFastqPath", required = false)
  val baseFastqPath: String = ""

  @Hidden
  @Argument(doc = "Run single sample germline calling in resulting bam", fullName = "doSingleSampleCalling", shortName = "call", required = false)
  var doSingleSampleCalling: Boolean = false

  @Hidden
  @Argument(doc = "Do post-recalibration to get BQSR statistics", fullName = "doPostRecal", shortName = "postRecal", required = false)
  var doPostRecal: Boolean = false

  @Hidden
  @Argument(doc = "BWA Parameteres", fullName = "bwa_parameters", shortName = "bp", required = false)
  val bwaParameters: String = " -q 5 -l 32 -k 2 -o 1 "

  @Hidden
  @Argument(doc = "Base path for Picard executables", fullName = "picardBase", shortName = "picardBase", required = false)
  val picardBase: String = "/opt/picard-metrics/"

  val cleaningExtension: String = ".clean.bam"
  val headerVersion: String = "#FILE1,FILE2,INDIVIDUAL,SAMPLE,LIBRARY,SEQUENCING,TUMOR,PLATFORM,PLATFORM_UNIT,CENTER,DESCRIPTION,DATE_SEQUENCED"

  // Defined utility methods. NOTE: You cannot call these in the constructor!
  // var / val will NOT work as the metadata context has to be injected after the object construction.
  private def individualMetadata: IndividualMetadata = getIndividual(individual)

  private def sampleMetadatas: Seq[SampleMetadata] = individualMetadata.sampleMetadatas

  /** **************************************************************************
    * Main script
    * ***************************************************************************/

  def script() {

    // todo -- preprocess metadata

    // there are 2 cases here to allow for easier testing
    val lanes: Seq[MetaInfo] =
      if (metaData == null) {
        // the prod way where we get metadata from a service
        sampleMetadatas.flatMap(sampleMetadata => {
          for (files <- sampleMetadata.getFiles("fastq"))
          yield (files(0), files(1), sampleMetadata)

        }).zipWithIndex.map {
          case ((file1, file2, sampleMetadata), index) => new MetaInfo(index + 1, file1, file2, sampleMetadata)
        }
      } else {
        // the dev way, where a metadata file is specified
        var lanesFromFile = Seq[MetaInfo]()
        var id = 1
        for (line: String <- Source.fromFile(metaData).getLines()) {
          if (line.startsWith("#")) {
            checkMetaDataHeader(line)
          }
          else {
            lanesFromFile :+= new MetaInfo(id, line.split(","))
            id = id + 1
          }
        }
        lanesFromFile
      }

    // todo -- align fastQ's from lanes object
    val samples = new mutable.HashMap[String, Seq[File]]()

    // todo -- parse all the sample level metadata into a single structure (not per lane)
    val tumorInfo = new mutable.HashMap[String, Boolean]()

    for (meta <- lanes) {
      val bamFile = performAlignment(meta)
      if (samples.contains(meta.sample)) {
        samples.put(meta.sample, samples(meta.sample) ++ Seq(bamFile))
      }
      else {
        samples.put(meta.sample, Seq(bamFile))
      }

      if (tumorInfo.contains(meta.sample)) {
        // check that the type of this sample hasn't changed
        assert(meta.tumor == tumorInfo(meta.sample),
          String.format("Tumor type for sample %s is internally inconsistent within metadata file.  Found %s and %s\n", meta.sample, meta.tumor.toString, tumorInfo(meta.sample).toString))
      } else {
        tumorInfo.put(meta.sample, meta.tumor)
      }
     }
    // todo -- add optional bam de-multiplexing and re-multiplexing pipeline


    var normalBAMs = Seq[File]()
    var tumorBAMs  = Seq[File]()

    for ((sample, status) <- tumorInfo) {
      val sampleBAM = new File(sample + ".bam")
      if (status) // tumor
        tumorBAMs :+= sampleBAM
      else  // normal
        normalBAMs :+= sampleBAM

    }

    val hasTumorBAMs = (!tumorBAMs.isEmpty)
    val hasNormalBAMs = (!normalBAMs.isEmpty)


    //println(tumorBAMs)
    //println(normalBAMs)
    if (tumorBAMs.size > 1) {
      throw new UserException("Bad inputs to processing pipeline. Only one tumor sample currently supported")
    }
    if (normalBAMs.size > 1) {
      throw new UserException("Bad inputs to processing pipeline. Only one normal sample currently supported")

    }

    var allBAMs = Seq[File]()
    for ((sample, bams) <- samples) {
      val sampleBAM = new File(sample + ".bam")
      allBAMs +:= sampleBAM
      add(joinBAMs(bams, sampleBAM))
    }

    clean(allBAMs)

    for (bam <- allBAMs) {
      val cleanBAM = swapExt(bam, ".bam", cleaningExtension)
      val dedupBAM = swapExt(bam, ".bam", ".clean.dedup.bam")
      val recalBAM = swapExt(bam, ".bam", ".clean.dedup.recal.bam")
      val reducedBAM = swapExt(bam, ".bam", ".clean.dedup.recal.reduced.bam")
      val duplicateMetricsFile = swapExt(bam, ".bam", ".duplicateMetrics")
      val preRecalFile = swapExt(bam, ".bam", ".pre_recal.table")
      val postRecalFile = swapExt(bam, ".bam", ".post_recal.table")
      val outVCF = swapExt(reducedBAM, ".bam", ".vcf")

      add(dedup(cleanBAM, dedupBAM, duplicateMetricsFile))

      recalibrate(dedupBAM, preRecalFile, postRecalFile, recalBAM)

      if (!qscript.skipQCMetrics) {
        if (qscript.targets != null && qscript.baits != null) {
          add(calculateHSMetrics(recalBAM, swapExt(recalBAM, ".bam", ".hs_metrics")))
        }
        // collect QC metrics based on full BAM
        val outGcBiasMetrics = swapExt(recalBAM, ".bam", ".gc_metrics")
        val outMultipleMetrics = swapExt(recalBAM, ".bam", ".multipleMetrics")

        add(calculateGCMetrics(recalBAM, outGcBiasMetrics))
        add(calculateMultipleMetrics(recalBAM, outMultipleMetrics))

      }


      add(reduce(recalBAM, reducedBAM))




      // add single sample vcf germline calling
      if (qscript.doSingleSampleCalling) {
        add(call(reducedBAM, outVCF))
        // report output parameters
        addRemoteOutput(individual, "singleSampleVCF", outVCF)
        addRemoteOutput(individual, "singleSampleVCFIndex", outVCF + ".idx")
      }
    }
    // todo hotfix: there is not a way right now to look up the BAM for a sample?
    /*    val normalName = tumorInfo.filter( P => P._2 == 0).keysIterator.next()
        val tumorName = tumorInfo.filter( P => P._2 == 1).keysIterator.next()
        val normalBam = normalName + ".clean.dedup.recal.bam"
        val tumorBam = tumorName + ".clean.dedup.recal.bam"
    */

    // this would be cleaner when the aforementioned is resolved
    val normalBam = swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.bam")
    val tumorBam = swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.bam")
    add(contest(tumorBam, normalBam))
    add(contest(normalBam, normalBam))

  //  print(allBAMs)
    if (hasNormalBAMs) {
      addRemoteOutput(individual, "unreducedNormalBAM", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.bam"))
      addRemoteOutput(individual, "unreducedNormalBAMIndex", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.bai"))
      addRemoteOutput(individual, "reducedNormalBAM", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.reduced.bam"))
      addRemoteOutput(individual, "reducedNormalBAMIndex", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.reduced.bai"))
      addRemoteOutput(individual, "normalHSMetrics", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.hs_metrics"))
      addRemoteOutput(individual, "normalGCMetrics", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.gc_metrics"))
      addRemoteOutput(individual, "normalInsertSizeMetrics", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.multipleMetrics.insert_size_metrics"))
      addRemoteOutput(individual, "normalAlignmentMetrics", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.multipleMetrics.alignment_summary_metrics"))
      addRemoteOutput(individual, "normalQualityByCycleMetrics", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.multipleMetrics.quality_by_cycle_metrics"))
      addRemoteOutput(individual, "normalQualityDistributionMetrics", swapExt(normalBAMs(0), ".bam", ".clean.dedup.recal.multipleMetrics.quality_distribution_metrics"))
      addRemoteOutput(individual, "normalQualityDistributionMetrics", swapExt(normalBAMs(0), ".bam", ".multipleMetrics.quality_distribution_metrics"))
      addRemoteOutput(individual, "normalDuplicateMetrics", swapExt(normalBAMs(0), ".bam", ".duplicateMetrics"))
      addRemoteOutput(individual, "normalContEstMetrics", swapExt(normalBAMs(0), ".bam", ".contamination.txt"))
      addRemoteOutput(individual, "normalContEstValue", swapExt(normalBAMs(0), ".bam", ".contamination.txt.firehose"))
    }

    if (hasTumorBAMs) {
      addRemoteOutput(individual, "unreducedTumorBAM", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.bam"))
      addRemoteOutput(individual, "unreducedTumorBAMIndex", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.bai"))
      addRemoteOutput(individual, "reducedTumorBAM", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.reduced.bam"))
      addRemoteOutput(individual, "reducedTumorBAMIndex", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.reduced.bai"))
      addRemoteOutput(individual, "tumorHSMetrics", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.hs_metrics"))
      addRemoteOutput(individual, "tumorGCMetrics", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.gc_metrics"))
      addRemoteOutput(individual, "tumorInsertSizeMetrics", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.multipleMetrics.insert_size_metrics"))
      addRemoteOutput(individual, "tumorAlignmentMetrics", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.multipleMetrics.alignment_summary_metrics"))
      addRemoteOutput(individual, "tumorQualityByCycleMetrics", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.multipleMetrics.quality_by_cycle_metrics"))
      addRemoteOutput(individual, "tumorQualityDistributionMetrics", swapExt(tumorBAMs(0), ".bam", ".clean.dedup.recal.multipleMetrics.quality_distribution_metrics"))
      addRemoteOutput(individual, "tumorQualityDistributionMetrics", swapExt(tumorBAMs(0), ".bam", ".multipleMetrics.quality_distribution_metrics"))
      addRemoteOutput(individual, "tumorDuplicateMetrics", swapExt(tumorBAMs(0), ".bam", ".duplicateMetrics"))
      addRemoteOutput(individual, "tumorContEstMetrics", swapExt(tumorBAMs(0), ".bam", ".contamination.txt"))
      addRemoteOutput(individual, "tumorContEstValue", swapExt(tumorBAMs(0), ".bam", ".contamination.txt.firehose"))
    }

  }

  /** **************************************************************************
    * Helper classes and methods
    * ***************************************************************************/

  private class MetaInfo(
                          val id: Int,
                          val file1: File,
                          val file2: File,
                          val individual: String,
                          val sample: String,
                          val library: String,
                          val sequencing: String,
                          val tumor: Boolean,
                          val platform: NGSPlatform,
                          val platformUnit: String,
                          val center: String,
                          val description: String,
                          val dateSequenced: String
                          ) {

    def this(id: Int, file1: File, file2: File, sampleMetadata: SampleMetadata) {
      this(
        id,
        file1,
        file2,
        individualMetadata.name,
        sampleMetadata.name,
        sampleMetadata.library,
        sampleMetadata.sequencing,
        sampleMetadata.isTumor,
        NGSPlatform.fromReadGroupPL(sampleMetadata.platform),
        "",
        sampleMetadata.center,
        "",
        new Date(sampleMetadata.dateSequenced).toString
      )
    }

    def this(idP: Int, headerArray: Array[String]) =
      this(
        idP,
        new File(qscript.baseFastqPath + headerArray(0)),
        if (headerArray(1).isEmpty) {
          null
        } else {
          new File(qscript.baseFastqPath + headerArray(1))
        },
        headerArray(2),
        headerArray(3),
        headerArray(4),
        headerArray(5),
        headerArray(6) == "1",
        NGSPlatform.fromReadGroupPL(headerArray(7)),
        headerArray(8),
        headerArray(9),
        headerArray(10),
        headerArray(11)
      )

    def bamFileName = individual + "." + sample + "." + library + "." + sequencing + "." + id + "." + tumor + ".bam"

    def readGroupString = "@RG\tID:%d\tCN:%s\tDS:%s\tDT:%s\tLB:%s\tPL:%s\tPU:%s\tSM:%s".format(id, center, description, dateSequenced, library, platform, platformUnit, sample)
  }

  def checkMetaDataHeader(header: String) {
    assert(header == headerVersion,
      String.format("Your header doesn't match the header this version of the pipeline is expecting.\n\tYour header: %s\n\t Our header: %s\n", header, headerVersion))
  }


  /**
   * BWA alignment for the lane (pair ended or not)
   *
   * @return an aligned bam file for the lane
   */
  def performAlignment(metaInfo: MetaInfo): File = {
    val saiFile1: File = new File(metaInfo.file1.getName + ".1.sai")
    val saiFile2: File = new File(metaInfo.file2.getName + ".2.sai")
    val alnSAM: File = new File(metaInfo.file1.getName + ".sam")
    val alnBAM: File = new File(metaInfo.bamFileName)

    add(bwa(" ", metaInfo.file1, saiFile1))

    if (!metaInfo.file2.isEmpty) {
      add(bwa(" ", metaInfo.file2, saiFile2),
        bwa_sam_pe(metaInfo.file1, metaInfo.file2, saiFile1, saiFile2, alnSAM, metaInfo.readGroupString))
    }
    else {
      add(bwa_sam_se(metaInfo.file1, saiFile1, alnSAM, metaInfo.readGroupString))
    }
    add(sortSam(alnSAM, alnBAM, SortOrder.coordinate))

    alnBAM
  }

  def clean(allBAMs: Seq[File]) {
    val bam: File = allBAMs(0)
    val targetIntervals = swapExt(bam, ".bam", ".cleaning.interval_list")
    add(target(allBAMs, targetIntervals), indel(allBAMs, targetIntervals))
  }

  def recalibrate(dedupBAM: File, preRecalFile: File, postRecalFile: File, recalBAM: File) {
    add(bqsr(dedupBAM, preRecalFile),
      apply_bqsr(dedupBAM, preRecalFile, recalBAM))

    if (qscript.doPostRecal) {
      add(bqsr(recalBAM, postRecalFile))
    }
  }


  /** **************************************************************************
    * Classes (GATK Walkers)
    * ***************************************************************************/


  // General arguments to non-GATK tools
  trait ExternalCommonArgs extends CommandLineFunction {
    this.memoryLimit = qscript.memLimit
    this.isIntermediate = true
  }

  // General arguments to GATK walkers
  trait CommandLineGATKArgs extends CommandLineGATK with ExternalCommonArgs {
    this.reference_sequence = qscript.reference
  }

  trait SAMargs extends PicardBamFunction with ExternalCommonArgs {
    this.maxRecordsInRam = 100000
  }

  case class target(inBAMs: Seq[File], outIntervals: File) extends RealignerTargetCreator with CommandLineGATKArgs {
    this.input_file = inBAMs
    this.out = outIntervals
    this.mismatchFraction = 0.0
    this.known ++= qscript.dbSNP
    if (indelSites != null)
      this.known ++= qscript.indelSites
    this.scatterCount = nContigs
    this.analysisName = outIntervals + ".target"
    this.jobName = outIntervals + ".target"
    this.intervals :+= qscript.targets
  }

  case class indel(inBAMs: Seq[File], tIntervals: File) extends IndelRealigner with CommandLineGATKArgs {
    // TODO -- THIS IS A WORKAROUND FOR QUEUE'S LIMITATION OF TRACKING LISTS OF FILES (implementation limited to 5 files)
    @Output(doc = "first cleaned bam file", required = true)
    @Gather(classOf[BamGatherFunction])
    var out1: File = swapExt(inBAMs(0), ".bam", cleaningExtension)
    @Output(doc = "first cleaned bam file index", required = true)
    @Gather(enabled = false)
    var ind1: File = swapExt(out1, ".bam", ".bai")
    @Output(doc = "2nd cleaned bam file", required = false)
    @Gather(classOf[BamGatherFunction])
    var out2: File = if (inBAMs.length >= 2) {
      swapExt(inBAMs(1), ".bam", cleaningExtension)
    } else {
      null
    }
    @Output(doc = "2nd cleaned bam file index", required = false)
    @Gather(enabled = false)
    var ind2: File = if (inBAMs.length >= 2) {
      swapExt(out2, ".bam", ".bai")
    } else {
      null
    }


    @Output(doc = "first cleaned bam file", required = false) var out3: File = if (inBAMs.length >= 3) {
      swapExt(inBAMs(2), ".bam", cleaningExtension)
    } else {
      null
    }
    @Output(doc = "first cleaned bam file", required = false) var ind3: File = if (inBAMs.length >= 3) {
      swapExt(out3, ".bam", ".bai")
    } else {
      null
    }
    @Output(doc = "first cleaned bam file", required = false) var out4: File = if (inBAMs.length >= 4) {
      swapExt(inBAMs(3), ".bam", cleaningExtension)
    } else {
      null
    }
    @Output(doc = "first cleaned bam file", required = false) var ind4: File = if (inBAMs.length >= 4) {
      swapExt(out4, ".bam", ".bai")
    } else {
      null
    }
    @Output(doc = "first cleaned bam file", required = false) var out5: File = if (inBAMs.length >= 5) {
      swapExt(inBAMs(4), ".bam", cleaningExtension)
    } else {
      null
    }
    @Output(doc = "first cleaned bam file", required = false) var ind5: File = if (inBAMs.length >= 5) {
      swapExt(out5, ".bam", ".bai")
    } else {
      null
    }
    this.input_file = inBAMs
    this.targetIntervals = tIntervals

    // FIXME - nWayOut doesn't seem to work, for now really only support single sample BAMs


    if (inBAMs.length == 1) {
      this.o = out1
    } else {
      this.nWayOut = cleaningExtension
    }
    this.known ++= qscript.dbSNP
    if (qscript.indelSites != null)
      this.known ++= qscript.indelSites
    this.consensusDeterminationModel = ConsensusDeterminationModel.USE_READS
    this.compress = 0
    this.noPGTag = qscript.testMode
    this.scatterCount = nContigs
    this.analysisName = inBAMs(0).toString + "clean"
    this.jobName = inBAMs(0).toString + ".clean"
    this.compress = Some(0) // no compression to save time
  }

  case class bqsr(inBAM: File, outRecalFile: File) extends BaseRecalibrator with CommandLineGATKArgs {
    this.knownSites ++= qscript.dbSNP
    this.covariate ++= Seq("ReadGroupCovariate", "QualityScoreCovariate", "CycleCovariate", "ContextCovariate")
    this.input_file :+= inBAM
    this.disable_indel_quals = true
    this.out = outRecalFile
    if (!defaultPlatform.isEmpty) this.default_platform = defaultPlatform
    //   this.scatterCount = nContigs    // not working in GATK
    this.analysisName = outRecalFile + ".covariates"
    this.jobName = outRecalFile + ".covariates"
    if (qscript.quick) this.intervals :+= qscript.targets
    this.memoryLimit = Some(4) // needs 4 GB to store big tables in memory
    this.nct = Some(qscript.numThreads)
  }

  case class apply_bqsr(inBAM: File, inRecalFile: File, outBAM: File) extends PrintReads with CommandLineGATKArgs {
    this.input_file :+= inBAM
    this.BQSR = inRecalFile
    this.baq = CalculationMode.CALCULATE_AS_NECESSARY
    this.out = outBAM
    // this.scatterCount = nContigs         // no need to scatter if nct enabled
    this.isIntermediate = false
    this.analysisName = outBAM + ".recalibration"
    this.jobName = outBAM + ".recalibration"
    this.nct = Some(qscript.numThreads)
    if (qscript.quick) this.intervals :+= qscript.targets
  }

  case class reduce(inBAM: File, outBAM: File) extends ReduceReads with CommandLineGATKArgs {
    this.input_file :+= inBAM
    this.out = outBAM
    this.isIntermediate = false
    this.analysisName = outBAM + ".reduce"
    this.jobName = outBAM + ".reduce"
    this.memoryLimit = Some(4)
    if (qscript.quick) this.intervals :+= qscript.targets

  }

  case class call(inBAM: File, outVCF: File) extends UnifiedGenotyper with CommandLineGATKArgs {
    this.input_file :+= inBAM
    this.out = outVCF
    this.isIntermediate = false
    this.analysisName = outVCF + ".singleSampleCalling"
    this.jobName = outVCF + ".singleSampleCalling"
    this.dbsnp = qscript.dbSNP(0)
    this.downsample_to_coverage = 600
    this.genotype_likelihoods_model = org.broadinstitute.sting.gatk.walkers.genotyper.GenotypeLikelihoodsCalculationModel.Model.BOTH
    this.out_mode = UnifiedGenotyperEngine.OUTPUT_MODE.EMIT_ALL_SITES
    this.genotyping_mode = GenotypeLikelihoodsCalculationModel.GENOTYPING_MODE.GENOTYPE_GIVEN_ALLELES
    this.alleles = qscript.knownSites
    this.scatterCount = nContigs

    this.intervals :+= qscript.targets
  }


  /** **************************************************************************
    * Classes (non-GATK programs)
    * ***************************************************************************/


  case class dedup(inBAM: File, outBAM: File, metricsFile: File) extends MarkDuplicates with ExternalCommonArgs {
    this.input :+= inBAM
    this.output = outBAM
    this.metrics = metricsFile
    //this.memoryLimit = 4
    this.analysisName = outBAM + ".dedup"
    this.jobName = outBAM + ".dedup"
    this.assumeSorted = Some(true)
    this.isIntermediate = false
  }

  case class calculateHSMetrics(inBAM: File, outFile: File) extends CalculateHsMetrics with ExternalCommonArgs {
    @Output(doc = "Metrics output", required = false) var ouths: File = outFile
    this.isIntermediate = false
    this.reference = qscript.reference
    this.input :+= inBAM
    this.output = outFile
    this.targets = qscript.targets
    this.baits = qscript.baits
    this.analysisName = outFile + ".hsMetrics"
    this.jobName = outFile + ".hsMetrics"
    this.jarFile = new File(qscript.picardBase + "CalculateHsMetrics.jar")
    // todo - do we want to compute per-read group HS metrics?

  }

  case class calculateGCMetrics(inBAM: File, outFile: File) extends CollectGcBiasMetrics with ExternalCommonArgs {
    @Output(doc = "Metrics output", required = false) var outgc: File = outFile
    this.reference = qscript.reference
    this.isIntermediate = false
    this.input :+= inBAM
    this.output = outFile
    this.analysisName = inBAM + ".gcMetrics"
    this.jobName = inBAM + ".gcMetrics"
    this.jarFile = new File(qscript.picardBase + "CollectGcBiasMetrics.jar")
  }

  case class calculateMultipleMetrics(inBAM: File, outFile: File) extends CollectMultipleMetrics with ExternalCommonArgs {
    @Output(doc = "Metrics output", required = false) var outmm: File = outFile
    this.reference = qscript.reference
    this.input :+= inBAM
    this.isIntermediate = false
    this.output = outFile
    this.analysisName = inBAM + ".multipleMetrics"
    this.jobName = inBAM + ".multipleMetrics"
    this.jarFile = new File(qscript.picardBase + "CollectMultipleMetrics.jar")
  }

  case class joinBAMs(inBAMs: Seq[File], outBAM: File) extends MergeSamFiles with ExternalCommonArgs {
    this.input = inBAMs
    this.output = outBAM
    this.analysisName = outBAM + ".joinBAMs"
    this.jobName = outBAM + ".joinBAMs"
  }

  case class sortSam(inSam: File, outBAM: File, sortOrderP: SortOrder) extends SortSam with ExternalCommonArgs {
    this.input :+= inSam
    this.output = outBAM
    this.sortOrder = sortOrderP
    this.analysisName = outBAM + ".sortSam"
    this.jobName = outBAM + ".sortSam"
  }

  case class validate(inBAM: File, outLog: File) extends ValidateSamFile with ExternalCommonArgs {
    this.input :+= inBAM
    this.output = outLog
    this.REFERENCE_SEQUENCE = qscript.reference
    this.isIntermediate = false
    this.analysisName = outLog + ".validate"
    this.jobName = outLog + ".validate"
  }

  case class revert(inBAM: File, outBAM: File, removeAlignmentInfo: Boolean) extends RevertSam with ExternalCommonArgs {
    this.output = outBAM
    this.input :+= inBAM
    this.removeAlignmentInformation = removeAlignmentInfo
    this.sortOrder = if (removeAlignmentInfo) {
      SortOrder.queryname
    } else {
      SortOrder.coordinate
    }
    this.analysisName = outBAM + "revert"
    this.jobName = outBAM + ".revert"
  }

  case class convertToFastQ(inBAM: File, outFQ: File) extends SamToFastq with ExternalCommonArgs {
    this.input :+= inBAM
    this.fastq = outFQ
    this.analysisName = outFQ + "convert_to_fastq"
    this.jobName = outFQ + ".convert_to_fastq"
  }

  case class bwa_sam_se(inBAM: File, inSai: File, outBAM: File, readGroupString: String) extends CommandLineFunction with ExternalCommonArgs {
    @Input(doc = "bam file to be aligned") var bam = inBAM
    @Input(doc = "bwa alignment index file") var sai = inSai
    @Output(doc = "output aligned bam file") var alignedBam = outBAM

    def commandLine = bwaPath + " samse " + reference + " " + sai + " " + bam + " -r \"" + readGroupString + "\" > " + alignedBam

    this.memoryLimit = 6
    this.analysisName = outBAM + ".bwa_sam_se"
    this.jobName = outBAM + ".bwa_sam_se"
  }

  case class bwa_sam_pe(inFile1: File, inFile2: File, inSai1: File, inSai2: File, outBAM: File, readGroupString: String) extends CommandLineFunction with ExternalCommonArgs {
    @Input(doc = "First file to be aligned") var first = inFile1
    @Input(doc = "Second file to be aligned") var second = inFile2
    @Input(doc = "bwa alignment index file for 1st mating pair") var sai1 = inSai1
    @Input(doc = "bwa alignment index file for 2nd mating pair") var sai2 = inSai2
    @Output(doc = "output aligned bam file") var alignedBam = outBAM

    def commandLine = bwaPath + " sampe " + reference + " " + sai1 + " " + sai2 + " " + first + " " + second + " -r \"" + readGroupString + "\" > " + alignedBam

    this.memoryLimit = 4
    this.analysisName = outBAM + ".bwa_sam_pe"
    this.jobName = outBAM + ".bwa_sam_pe"
  }

  case class bwa_sw(inFastQ: File, outBAM: File) extends CommandLineFunction with ExternalCommonArgs {
    @Input(doc = "fastq file to be aligned") var fq = inFastQ
    @Output(doc = "output bam file") var bam = outBAM

    def commandLine = bwaPath + " bwasw -t " + numThreads + " " + reference + " " + fq + " > " + bam

    this.analysisName = outBAM + ".bwasw"
    this.jobName = outBAM + ".bwasw"
  }


  case class bwa(inputParms: String, inBAM: File, outSai: File) extends CommandLineFunction {
    @Input(doc = "bam file to be aligned") var bam = inBAM
    @Output(doc = "output sai file") var sai = outSai

    def commandLine = bwaPath + " aln -t " + numThreads + bwaParameters + reference + inputParms + bam + " > " + sai

    this.analysisName = outSai + ".bwa_aln_se"
    this.jobName = outSai + ".bwa_aln_se"
    this.memoryLimit = Some(5)
    this.isIntermediate = true

  }

  case class writeList(inBAMs: Seq[File], outBAMList: File) extends ListWriterFunction {
    this.inputFiles = inBAMs
    this.listFile = outBAMList
    this.analysisName = outBAMList + ".bamList"
    this.jobName = outBAMList + ".bamList"
  }

  case class contest(inBAM: File, inNormalBam: File) extends CommandLineFunction {
    @Input(doc = "bam file for contamination estimation") var bam = inBAM
    @Input(doc = "bam file for genotype bootstrap") var normalBam = inNormalBam
    @Output(doc = "output contamination file") var outContam = swapExt(bam, ".bam", ".contamination.txt")
    @Output(doc = "output contamination file single value") var outContamValue = swapExt(bam, ".bam", ".contamination.txt.firehose")

    def commandLine =
      contestBin + " -reference " + reference + " -interval " + targets +
      " -array none -faf true " +
      " -bam " + bam + " -nbam " + normalBam +
      " -array_interval " + contestArrayIntervals +
      " -pop " + contestPopFrequencies +
      " -out " + outContam +
      " -run"

    this.analysisName = outContam + ".ContEst"
    this.memoryLimit = Some(2)
  }


}

