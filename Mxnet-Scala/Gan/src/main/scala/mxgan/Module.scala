package mxgan

import org.apache.mxnet.Symbol
import org.apache.mxnet.Context
import org.apache.mxnet.Shape
import org.apache.mxnet.Optimizer
import org.apache.mxnet.NDArray
import org.apache.mxnet.Initializer
import org.apache.mxnet.DataBatch
import org.apache.mxnet.Random
import org.apache.mxnet.util.OptionConversion._

class GANModule(
              symbolGenerator: Symbol,
              symbolEncoder: Symbol,
              context: Context,
              dataShape: Shape,
              codeShape: Shape,
              posLabel: Float = 0.9f) {
  
  // generator
  private val gDataLabelShape = Map("code" -> codeShape)
  private val (gArgShapes, gOutShapes, gAuxShapes) = symbolGenerator.inferShape(gDataLabelShape)
  
  private val gArgNames = symbolGenerator.listArguments()
  private val gArgDict = gArgNames.zip(gArgShapes.map(NDArray.empty(_, context))).toMap

  private val gGradDict = gArgNames.zip(gArgShapes).filter { case (name, shape) =>
    !gDataLabelShape.contains(name)
  }.map(x => x._1 -> NDArray.empty(x._2, context) ).toMap
  
  private val gData = gArgDict("code") // equals to self.temp_rbatch 

  val gAuxNames = symbolGenerator.listAuxiliaryStates()
  val gAuxDict = gAuxNames.zip(gAuxShapes.map(NDArray.empty(_, context))).toMap
  private val gExecutor = symbolGenerator.bind(context, gArgDict, gGradDict, "write", gAuxDict, null, null)

  // discriminator
  private val batchSize = dataShape(0)
  private var encoder = symbolEncoder
  encoder = Symbol.api.FullyConnected(encoder, num_hidden = 1, name = "fc_dloss")
  encoder = Symbol.api.LogisticRegressionOutput(encoder, name = "dloss")
  
  private val dDataShape = Map("data" -> dataShape)
  private val dLabelShape = Map("dloss_label" -> Shape(batchSize))
  private val (dArgShapes, _, dAuxShapes) = encoder.inferShape(dDataShape ++ dLabelShape)
  
  private val dArgNames = encoder.listArguments()
  private val dArgDict = dArgNames.zip(dArgShapes.map(NDArray.empty(_, context))).toMap

  private val dGradDict = dArgNames.zip(dArgShapes).filter { case (name, shape) =>
    !dLabelShape.contains(name)
  }.map(x => x._1 -> NDArray.empty(x._2, context) ).toMap
  
  private val tempGradD = dArgNames.zip(dArgShapes).filter { case (name, shape) =>
    !dLabelShape.contains(name)
  }.map(x => x._1 -> NDArray.empty(x._2, context) ).toMap
  
  private val dData = dArgDict("data") // equals to self.temp_rbatch
  val dLabel = dArgDict("dloss_label") // equals to self.temp_label

  val dAuxNames = encoder.listAuxiliaryStates()
  val dAuxDict = dAuxNames.zip(dAuxShapes.map(NDArray.empty(_, context))).toMap
  private val dExecutor = encoder.bind(context, dArgDict, dGradDict, "write", dAuxDict, null, null)

  val tempOutG = gOutShapes.map(NDArray.empty(_, context)).toArray
  val tempDiffD: NDArray = dGradDict("data")
  
  var outputsFake: Array[NDArray] = null
  var outputsReal: Array[NDArray] = null

  def initGParams(initializer: Initializer): Unit = {
    gArgDict.filter(x => !gDataLabelShape.contains(x._1)).foreach { case (name, ndArray) => initializer(name, ndArray) }
  }
  
  def initDParams(initializer: Initializer): Unit = {
    dArgDict.filter(x => !dDataShape.contains(x._1) && !dLabelShape.contains(x._1))
                   .foreach { case (name, ndArray) => initializer(name, ndArray) }
  }

  // put to init_optimizer
  private var gOpt: Optimizer = null
  private var gParamsGrads: List[(Int, String, NDArray, AnyRef)] = null
  private var dOpt: Optimizer = null
  private var dParamsGrads: List[(Int, String, NDArray, AnyRef)] = null 
  
  def initOptimizer(opt: Optimizer): Unit = {
    gOpt = opt
    gParamsGrads = gGradDict.toList.zipWithIndex.map { case ((name, grad), idx) =>
      (idx, name, grad, gOpt.createState(idx, gArgDict(name)))
    }
    dOpt = opt
    dParamsGrads = dGradDict.filter(x => !dDataShape.contains(x._1)).toList.zipWithIndex.map { case ((name, grad), idx) =>
      (idx, name, grad, dOpt.createState(idx, dArgDict(name)))
    }
  }
 
  private def saveTempGradD(): Unit = {
    val keys = this.dGradDict.keys
    for (k <- keys) {
      this.dGradDict(k).copyTo(this.tempGradD(k))
    }
  }
  
  // add back saved gradient
  private def addTempGradD(): Unit = {
    val keys = this.dGradDict.keys
    for (k <- keys) {
      val tmp = this.dGradDict(k) + this.tempGradD(k)
      this.dGradDict(k).set(tmp)
      tmp.dispose()
    }
  }
  
  // update the model for a single batch
  def update(dBatch: DataBatch): Unit = {
    // generate fake image
    this.gData.set(Random.normal(0, 1.0f, this.gData.shape, context))
    this.gExecutor.forward(isTrain = true)
    val outG = this.gExecutor.outputs(0)
    this.dLabel.set(0f)
    this.dData.set(outG)
    this.dExecutor.forward(isTrain = true)
    this.dExecutor.backward()
    this.saveTempGradD()
    // update generator
    this.dLabel.set(1f)
    this.dExecutor.forward(isTrain = true)
    this.dExecutor.backward()
    this.gExecutor.backward(tempDiffD)
    gParamsGrads.foreach { case (idx, name, grad, optimState) =>
      gOpt.update(idx, gArgDict(name), grad, optimState)
    }
    this.outputsFake = this.dExecutor.outputs.map(x => x.copy())
    // update discriminator
    this.dLabel.set(posLabel)
    this.dData.set(dBatch.data(0))
    this.dExecutor.forward(isTrain = true)
    this.dExecutor.backward()
    this.addTempGradD()
    dParamsGrads.foreach { case (idx, name, grad, optimState) =>
      dOpt.update(idx, dArgDict(name), grad, optimState)
    }
    this.outputsReal = this.dExecutor.outputs.map(x => x.copy())
    this.tempOutG.indices.foreach(i => this.tempOutG(i).set(this.gExecutor.outputs(i)))
  }
  
}
