package com.johnsnowlabs.ml.crf

import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.annotators.RegexTokenizer
import com.johnsnowlabs.nlp.annotators.common.Annotated.{NerTaggedSentence, PosTaggedSentence}
import com.johnsnowlabs.nlp.annotators.common.{NerTagged, PosTagged, TaggedSentence}
import com.johnsnowlabs.nlp.annotators.ner.crf.{CrfBasedNer}
import com.johnsnowlabs.nlp.annotators.pos.perceptron.PerceptronApproach
import com.johnsnowlabs.nlp.annotators.sbd.pragmatic.SentenceDetectorModel
import org.apache.spark.ml.{Pipeline, PipelineModel, PipelineStage}
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source


class CoNLL(val targetColumn: Int = 3, val spark: SparkSession = SparkAccessor.spark) {
  import spark.implicits._

  /*
    Reads Dataset in CoNLL format and pack it into docs
   */
  def readDocs(file: String): Seq[(String, Seq[Annotation])] = {
    val lines = Source.fromFile(file).getLines().toSeq

    readLines(lines)
  }

  def readLines(lines: Seq[String]): Seq[(String, Seq[Annotation])] = {
    val doc = new StringBuilder()
    val labels = new ArrayBuffer[Annotation]()

    val docs = lines
      .flatMap{line =>
        val items = line.split(" ")
        if (items.nonEmpty && items(0) == "-DOCSTART-") {
          val result = (doc.toString, labels.toList)
          doc.clear()
          labels.clear()

          if (result._1.nonEmpty)
            Some(result)
          else
            None
        } else if (items.length <= 1) {
          if (doc.nonEmpty && doc.last != '\n')
            doc.append("\n\n")
          None
        } else
        {
          if (doc.nonEmpty)
            doc.append(" ")

          val begin = doc.length
          doc.append(items(0))
          val end = doc.length - 1
          val ner = items(targetColumn)
          labels.append(new Annotation(AnnotatorType.NAMED_ENTITY, begin, end, ner, Map("tag" -> ner)))
          None
        }
      }

    val last = if (doc.nonEmpty) Seq((doc.toString, labels.toList)) else Seq.empty

    docs ++ last
  }

  def readDataset(file: String,
                  textColumn: String = "text",
                  labelColumn: String = "label"): Dataset[_] = {
    readDocs(file).toDF(textColumn, labelColumn)
  }

  def readDatasetFromLines(lines: Seq[String],
                           textColumn: String = "text",
                           labelColumn: String = "label"): Dataset[_] = {
    val seq = readLines(lines)
    seq.toDF(textColumn, labelColumn)
  }
}

object CoNLL2003PipelineTest extends App {
  val folder = "./"

  val trainFile = folder + "eng.train"
  val testFileA = folder + "eng.testa"
  val testFileB = folder + "eng.testb"

  val nerReader = new CoNLL()
  val posReader = new CoNLL(targetColumn = 1)

  def getPosStages(): Array[_ <: PipelineStage] = {
    val documentAssembler = new DocumentAssembler()
      .setInputCol("text")
      .setOutputCol("document")

    val sentenceDetector = new SentenceDetectorModel()
      .setCustomBoundChars(Array("\n\n"))
      .setInputCols(Array("document"))
      .setOutputCol("sentence")

    val tokenizer = new RegexTokenizer()
      .setInputCols(Array("document"))
      .setOutputCol("token")

    val posTagger = new PerceptronApproach()
      .setCorpusPath("/anc-pos-corpus/")
      .setNIterations(10)
      .setInputCols("token", "document")
      .setOutputCol("pos")

    Array(documentAssembler,
      sentenceDetector,
      tokenizer,
      posTagger)
  }

  def getNerStages(): Array[_ <: PipelineStage] = {

    val nerTagger = new CrfBasedNer()
      .setInputCols("sentence", "token", "pos")
      .setLabelColumn("label")
      .setC0(1250000)
      .setRandomSeed(100)
      .setDicts(Seq("src/main/resources/ner-corpus/dict.txt"))
      .setOutputCol("ner")

    getPosStages() :+ nerTagger
  }

  def trainPosModel(file: String): PipelineModel = {
    System.out.println("Dataset Reading")
    val time = System.nanoTime()
    val dataset = posReader.readDataset(file)
    System.out.println(s"Done, ${(System.nanoTime() - time)/1e9}\n")

    System.out.println("Start fitting")

    val stages = getPosStages()

    val pipeline = new Pipeline()
      .setStages(stages)

    pipeline.fit(dataset)
  }

  def trainNerModel(file: String): PipelineModel = {
    System.out.println("Dataset Reading")
    val time = System.nanoTime()
    val dataset = nerReader.readDataset(file)
    System.out.println(s"Done, ${(System.nanoTime() - time)/1e9}\n")

    System.out.println("Start fitting")

    val stages = getNerStages()

    val pipeline = new Pipeline()
      .setStages(stages)

    pipeline.fit(dataset)
  }

  def calcStat(correct: Int, predicted: Int, predictedCorrect: Int): (Float, Float, Float) = {
    // prec = (predicted & correct) / predicted
    // rec = (predicted & correct) / correct
    val prec = predictedCorrect.toFloat / predicted
    val rec = predictedCorrect.toFloat / correct
    val f1 = 2 * prec * rec / (prec + rec)

    (prec, rec, f1)
  }

  def collectNerLabeled(df: DataFrame): Seq[(TextSentenceLabels, NerTaggedSentence)] = {
    NerTagged.collectLabeledInstances(
      df,
      Seq("sentence", "token", "ner"),
      "label"
    )
  }

  def collectPosLabeled(df: DataFrame): Seq[(TextSentenceLabels, PosTaggedSentence)] = {
    PosTagged.collectLabeledInstances(
      df,
      Seq("sentence", "token", "pos"),
      "label"
    )
  }

  def testDataset(file: String,
                  model: PipelineModel,
                  predictedColumn: String = "ner",
                  reader: CoNLL,
                  collect: DataFrame => Seq[(TextSentenceLabels, TaggedSentence)]
                 ): Unit = {
    val started = System.nanoTime()

    val predictedCorrect = mutable.Map[String, Int]()
    val predicted = mutable.Map[String, Int]()
    val correct = mutable.Map[String, Int]()

    val dataset = reader.readDataset(file)
    val transformed = model.transform(dataset)

    val sentences = collect(transformed)

    sentences.foreach{
      case (labels, taggedSentence) =>
        labels.labels.zip(taggedSentence.tags).foreach {
          case (label, tag) =>
            correct(label) = correct.getOrElse(label, 0) + 1
            predicted(tag) = predicted.getOrElse(tag, 0) + 1

            if (label == tag)
              predictedCorrect(tag) = predictedCorrect.getOrElse(tag, 0) + 1
        }
    }

    System.out.println(s"time: ${(System.nanoTime() - started)/1e9}")

    val labels = (correct.keys ++ predicted.keys).toSeq.distinct

    val notEmptyLabels = labels.filter(label => label != "O" && label.nonEmpty)

    val totalCorrect = correct.filterKeys(label => notEmptyLabels.contains(label)).values.sum
    val totalPredicted = correct.filterKeys(label => notEmptyLabels.contains(label)).values.sum
    val totalPredictedCorrect = predictedCorrect.filterKeys(label => notEmptyLabels.contains(label)).values.sum
    val (prec, rec, f1) = calcStat(totalCorrect, totalPredicted, totalPredictedCorrect)
    System.out.println(s"Total stat, prec: $prec\t, rec: $rec\t, f1: $f1")

    System.out.println("label\tprec\trec\tf1")

    for (label <- labels) {
      val (prec, rec, f1) = calcStat(
        correct.getOrElse(label, 0),
        predicted.getOrElse(label, 0),
        predictedCorrect.getOrElse(label, 0)
      )

      System.out.println(s"$label\t$prec\t$rec\t$f1")
    }
  }

  def measurePos(): PipelineModel = {
    val model = trainPosModel(trainFile)

    System.out.println("\n\nQuality on train data")
    testDataset(trainFile, model, "pos", posReader, collectPosLabeled)

    System.out.println("\n\nQuality on test A data")
    testDataset(testFileA, model, "pos", posReader, collectPosLabeled)

    System.out.println("\n\nQuality on test B data")
    testDataset(testFileB, model, "pos", posReader, collectPosLabeled)

    model
  }

  def measureNer(): PipelineModel = {
    val model = trainNerModel(trainFile)

    System.out.println("\n\nQuality on train data")
    testDataset(trainFile, model, "ner", nerReader, collectNerLabeled)

    System.out.println("\n\nQuality on test A data")
    testDataset(testFileA, model, "ner", nerReader, collectNerLabeled)

    System.out.println("\n\nQuality on test B data")
    testDataset(testFileB, model, "ner", nerReader, collectNerLabeled)

    model
  }

  val posModel = measurePos()
  posModel.write.overwrite().save("pos_model")

  val nerModel = measureNer()
  nerModel.write.overwrite().save("ner_model")
}
