/*
// Copyright (c) 2015 Intel Corporation 
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/

package org.trustedanalytics.atk.engine.model.plugins.libsvm

import org.trustedanalytics.atk.domain.frame.ClassificationMetricValue
import org.trustedanalytics.atk.domain.schema.DataTypes
import org.trustedanalytics.atk.engine.frame.plugins.{ ScoreAndLabel, ClassificationMetrics }
import org.trustedanalytics.atk.engine.model.Model
import org.trustedanalytics.atk.engine.plugin.{ ApiMaturityTag, Invocation, PluginDoc }
import org.trustedanalytics.atk.engine.frame.SparkFrame
import org.trustedanalytics.atk.engine.plugin.SparkCommandPlugin
import org.trustedanalytics.atk.domain.DomainJsonProtocol._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import LibSvmJsonProtocol._

@PluginDoc(oneLine = "Predict test frame labels and return metrics.",
  extended = """Predict the labels for a test frame and run classification
metrics on predicted and target labels.""",
  returns = """Object
    Object with binary classification metrics.
    The data returned is composed of multiple components:
<object>.accuracy : double
     The degree of correctness of the test frame labels.
<object>.confusion_matrix : table
    A specific table layout that allows visualization of the performance of the
    test.
<object>.f_measure : double
    A measure of a test's accuracy.
    It considers both the precision and the recall of the test to compute
    the score.
<object>.precision : double
    The degree to which the correctness of the label is expressed.
<object>.recall : double
     The fraction of relevant instances that are retrieved.""")
class LibSvmTestPlugin extends SparkCommandPlugin[LibSvmTestArgs, ClassificationMetricValue] {
  /**
   * The name of the command.
   *
   * The format of the name determines how the plugin gets "installed" in the client layer
   * e.g Python client via code generation.
   */
  override def name: String = "model:libsvm/test"

  override def apiMaturityTag = Some(ApiMaturityTag.Alpha)

  /**
   * Number of Spark jobs that get created by running this command
   * (this configuration is used to prevent multiple progress bars in Python client)
   */

  override def numberOfJobs(arguments: LibSvmTestArgs)(implicit invocation: Invocation) = 2

  /**
   * Get the predictions for observations in a test frame
   *
   * @param invocation information about the user and the circumstances at the time of the call,
   *                   as well as a function that can be called to produce a SparkContext that
   *                   can be used during this invocation.
   * @param arguments user supplied arguments to running this plugin
   * @return a value of type declared as the Return type.
   */
  override def execute(arguments: LibSvmTestArgs)(implicit invocation: Invocation): ClassificationMetricValue = {
    val model: Model = arguments.model
    val frame: SparkFrame = arguments.frame

    //Loading the model
    val svmColumns = arguments.observationColumns
    val libsvmData = model.data.convertTo[LibSvmData]
    val libsvmModel = libsvmData.svmModel

    if (arguments.observationColumns.isDefined) {
      require(libsvmData.observationColumns.length == arguments.observationColumns.get.length, "Number of columns for train and test should be same")
    }

    //predicting a label for the observation column/s
    val observationColumns = arguments.observationColumns.getOrElse(libsvmData.observationColumns)
    val scoreAndLabelRdd = frame.rdd.toScoreAndLabelRdd(row => {
      val vector = row.valuesAsDoubleArray(observationColumns).toVector
      val label = row.doubleValue(arguments.labelColumn)
      val score = LibSvmPluginFunctions.score(libsvmModel, vector)
      ScoreAndLabel(score.value, label)
    })

    //Run Binary classification metrics
    val posLabel: Double = 1d
    ClassificationMetrics.binaryClassificationMetrics(scoreAndLabelRdd, posLabel)
  }

}