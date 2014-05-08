package org.openml.tools.stream;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openml.apiconnector.algorithms.Conversion;
import org.openml.tools.algorithms.InstancesHelper;

import weka.classifiers.Classifier;
import weka.classifiers.evaluation.Evaluation;
import weka.classifiers.rules.ZeroR;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;

public class RunStreamDataset {
	private static final String OPENML_TASK_ID_ATT = "openml_task_id";
	private static final String OPENML_SCORE_ATT_PREFIX = "openml_classifier_";
	private static final String OPENML_CLASS_ATT = "class";
	
	private static final String[] metaAlgrorithms = {
			"moa.LeveragingBag_HoeffdingTree(1)", "moa.LeveragingBag_kNN(1)", 
			"moa.OzaBoostAdwin_HoeffdingTree(1)", "moa.OzaBagAdwin_HoeffdingTree(1)"
		};
	
	private static final String[] baseAlgorithms = {
		"moa.kNN(1)",
		"moa.HoeffdingTree(1)",
		"moa.SGD(1)",
		"moa.NaiveBayes(1)",
		"moa.SPegasos(1)"
	};
	
	private static final String[] batchIncremental = {
		"moa.WEKAClassifier_J48(1)", "moa.WEKAClassifier_OneR(1)", 
	};
	
	private final ArrayList<Attribute> scoreAttributes;
	private final ArrayList<Attribute> trainTestAttributes;
	private final Evaluation GLOBAL_EVALUATOR;
	private final Evaluation GLOBAL_BASELINE;
	private final BufferedWriter LOG_WRITER;
	private final BufferedWriter SQL_WRITER;
	private final RunStreamEvaluator GLOBAL_STREAM_EVALUATOR;
	
	private final Map<Integer, Integer> tasksAvailable;
	private final Instances allMeasurements;
	private final Integer taskIdIndex;
	
	public static void main( String[] args ) throws Exception {
		
		new RunStreamDataset();
		
	}
	
	public RunStreamDataset() throws Exception {
		tasksAvailable = new HashMap<Integer, Integer>();
		allMeasurements = new Instances( new BufferedReader( new FileReader( new File("meta_stream.arff") ) ) );
		filterClassAttributes( allMeasurements, baseAlgorithms );
		allMeasurements.setClass( allMeasurements.attribute( OPENML_CLASS_ATT ) );
		
		InstancesHelper.toFile( allMeasurements, "meta_stream_adjusted" );
		
		GLOBAL_EVALUATOR = new Evaluation( allMeasurements );
		GLOBAL_EVALUATOR.useNoPriors();
		GLOBAL_BASELINE = new Evaluation( allMeasurements );
		GLOBAL_BASELINE.useNoPriors();
		GLOBAL_STREAM_EVALUATOR = new RunStreamEvaluator( baseAlgorithms );
		
		LOG_WRITER = new BufferedWriter( new FileWriter( new File( "evaluator.log" ) ) );
		SQL_WRITER = new BufferedWriter( new FileWriter( new File( "curves.sql" ) ) );
		
		taskIdIndex = allMeasurements.attribute( OPENML_TASK_ID_ATT ).index();
		scoreAttributes = getScoreAttributes();
		trainTestAttributes = getTrainTestAttributes();
		
		
		for( int i = 0; i < allMeasurements.numInstances(); ++i ) {
			int taskId = (int) allMeasurements.instance( i ).value( taskIdIndex );
			if( tasksAvailable.containsKey( taskId ) == false ) {
				tasksAvailable.put( taskId, 1 );
			} else {
				tasksAvailable.put( taskId, tasksAvailable.get( taskId ) + 1 );
			}
		}
		
		System.out.println( tasksAvailable );
		SQL_WRITER.append( "INSERT INTO `tmp_curve`(`task_id`,`interval_start`,`score`,`baseline`,`max`) VALUES \n" );
		
		int counter = 0;
		for( Integer i : tasksAvailable.keySet() ) {
			Conversion.log( "[OK]", "[RunStream]", "Running task " + i + " ~ "+ tasksAvailable.get( i ) + " instances ("+(++counter)+"/"+tasksAvailable.keySet().size()+")" );
			evaluateTask( i );
			SQL_WRITER.append( GLOBAL_STREAM_EVALUATOR.getSql( i ) );
			Conversion.log( "[OK]", "[RunStream]", "Current score " + GLOBAL_EVALUATOR.pctCorrect() + ", Zero R: " + GLOBAL_BASELINE.pctCorrect() );
		}
		
		
		System.out.println( GLOBAL_EVALUATOR.toSummaryString() );
		System.out.println( GLOBAL_STREAM_EVALUATOR.toString() );
		
		LOG_WRITER.write( "Global Evaluation: " );
		LOG_WRITER.write( GLOBAL_EVALUATOR.toSummaryString() );
		LOG_WRITER.write( GLOBAL_EVALUATOR.toMatrixString( "Confussion Matrix" ) );
		LOG_WRITER.write( "Zero R: " );
		LOG_WRITER.write( GLOBAL_BASELINE.toSummaryString() );
		LOG_WRITER.write( GLOBAL_BASELINE.toMatrixString( "Confussion Matrix" ) );
		LOG_WRITER.write( "\n" + GLOBAL_STREAM_EVALUATOR.toString() );
		
		LOG_WRITER.close();
		SQL_WRITER.close();
	}
	
	private void evaluateTask( int task_id ) throws Exception {
		Integer instancesCount = tasksAvailable.get( task_id );
		Instances testSet = new Instances( "task_" + task_id + "_test", trainTestAttributes, instancesCount );
		Instances trainSet = new Instances( "task_" + task_id + "_train", trainTestAttributes, allMeasurements.size() - instancesCount );
		Instances scoreSet = new Instances( "task_" + task_id + "_scores", scoreAttributes, instancesCount);
		
		
		
		trainSet.setClass( trainSet.attribute( OPENML_CLASS_ATT ) );
		testSet.setClass( testSet.attribute( OPENML_CLASS_ATT ) );
		// first create the three instance sets...
		for( int i = 0; i < allMeasurements.numInstances(); ++i ) {
			int instanceTaskId = (int) allMeasurements.instance( i ).value( taskIdIndex );
			if( task_id == instanceTaskId ) {
				addPlainInstance( testSet, allMeasurements.instance( i ) );
				addScoreInstance( scoreSet, allMeasurements.instance( i ) );
			} else {
				addPlainInstance( trainSet, allMeasurements.instance( i ) );
			}
		}
		
		Classifier metaLearner = new RandomForest();
		Classifier baseline = new ZeroR();
		Evaluation evaluator = new Evaluation( trainSet );
		
		
		metaLearner.buildClassifier( trainSet );
		baseline.buildClassifier( trainSet );
		
		int interval_start_idx = testSet.attribute( "openml_interval_start" ).index();
		for( int i = 0; i < testSet.numInstances(); ++i ) {
			GLOBAL_EVALUATOR.evaluateModelOnceAndRecordPrediction( metaLearner, testSet.instance( i ) );
			GLOBAL_BASELINE.evaluateModelOnceAndRecordPrediction( baseline, testSet.instance( i ) );
			evaluator.evaluateModelOnceAndRecordPrediction( metaLearner, testSet.instance( i ) );
			
			int predictionIdx = (int) metaLearner.classifyInstance( testSet.instance( i ) );
			int baselinePredictionIdx = (int) baseline.classifyInstance( testSet.instance( i ) );
			
			String predictionStr = testSet.classAttribute().value( predictionIdx );
			String baselinePredictionStr = testSet.classAttribute().value( baselinePredictionIdx );

			GLOBAL_STREAM_EVALUATOR.addPrediction( task_id, (int) testSet.instance(i).value( interval_start_idx ), predictionStr, baselinePredictionStr, scoreSet.instance( i ) );
		}
		System.out.println( evaluator.toSummaryString() );
		LOG_WRITER.write( evaluator.toSummaryString() );
	}
	
	private ArrayList<Attribute> getTrainTestAttributes() {
		ArrayList<Attribute> result = new ArrayList<Attribute>();
		for( int i = 0; i < allMeasurements.numAttributes(); ++i ) {
			String attName = allMeasurements.attribute( i ).name();
			if( attName.equals( OPENML_TASK_ID_ATT ) ) {
				// do nothing
			} else if( attName.startsWith( OPENML_SCORE_ATT_PREFIX ) ) {
				// do nothing
			} else {
				result.add( InstancesHelper.copyAttribute( allMeasurements.attribute( i ) ) );
			}
		}
		return result;
	}
	
	private ArrayList<Attribute> getScoreAttributes() {
		ArrayList<Attribute> result = new ArrayList<Attribute>();
		for( int i = 0; i < allMeasurements.numAttributes(); ++i ) {
			String attName = allMeasurements.attribute( i ).name();
			if( attName.equals( OPENML_TASK_ID_ATT ) ) {
				// do nothing
			} else if( attName.startsWith( OPENML_SCORE_ATT_PREFIX ) ) {
				result.add( new Attribute( attName.substring( OPENML_SCORE_ATT_PREFIX.length() ) ) );
			}
		}
		return result;
	}
	
	private void addPlainInstance( Instances set, Instance otherInstance ) {
		Instance newInstance = new DenseInstance( set.numAttributes() );
		newInstance.setDataset( set );
		
		for( int i = 0; i < newInstance.numAttributes(); ++i ) {
			String attName = newInstance.attribute( i ).name() + "";
			int otherInstanceAttIndex = otherInstance.dataset().attribute( attName ).index();
			newInstance.setValue( i, otherInstance.value( otherInstanceAttIndex ) );
		}
		set.add( newInstance );
	}
	
	private void addScoreInstance( Instances set, Instance otherInstance ) {
		Instance newInstance = new DenseInstance( set.numAttributes() );
		newInstance.setDataset( set );
		
		for( int i = 0; i < newInstance.numAttributes(); ++i ) {
			String attName = newInstance.attribute( i ).name();
			int otherInstanceAttIndex = otherInstance.dataset().attribute( OPENML_SCORE_ATT_PREFIX + attName ).index();
			newInstance.setValue( i, otherInstance.value( otherInstanceAttIndex ) );
		}
		set.add( newInstance );
	}
	
	public void filterClassAttributes( Instances dataset, String[] usedAlgorithms ) {
		if( usedAlgorithms == null ) return;
		if( usedAlgorithms.length == 0 ) return;
		
		dataset.deleteAttributeAt( dataset.attribute( OPENML_CLASS_ATT ).index() );
		Attribute newClass = new Attribute( OPENML_CLASS_ATT, Arrays.asList( usedAlgorithms ) );
		dataset.insertAttributeAt( newClass, dataset.numAttributes() );
		dataset.setClass( newClass );

		for( int i = 0; i < dataset.numInstances(); ++i ) {
			double bestScore = -1.0;
			String bestClassifier = null;
			for( String current : usedAlgorithms ) {
				int attributeIndex = dataset.attribute( OPENML_SCORE_ATT_PREFIX + current ).index();
				
				if( dataset.instance( i ).value( attributeIndex ) > bestScore ) {
					bestScore = dataset.instance( i ).value( attributeIndex );
					bestClassifier = current;
				}
			}
			dataset.instance(i).setValue( dataset.numAttributes() - 1, bestClassifier );
		}
	}
}