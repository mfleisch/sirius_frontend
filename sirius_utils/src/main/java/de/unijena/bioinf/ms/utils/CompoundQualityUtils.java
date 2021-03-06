package de.unijena.bioinf.ms.utils;

import de.unijena.bioinf.ChemistryBase.SimpleRectangularIsolationWindow;
import de.unijena.bioinf.ChemistryBase.ms.*;
import de.unijena.bioinf.ChemistryBase.ms.ft.FTree;
import de.unijena.bioinf.ChemistryBase.ms.inputValidators.*;
import de.unijena.bioinf.sirius.IdentificationResult;
import de.unijena.bioinf.sirius.Ms2RunPreprocessor;
import de.unijena.bioinf.sirius.Sirius;
import de.unijena.bioinf.sirius.ExperimentResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CompoundQualityUtils {

    //todo here we need to clean up. it seams that there is no reason to copy everything since we just add
    // annotations -> here the idea should be, adding annotations specific for a task can not interfere with
    // other tasks and are save.
    // copying our main/immuatble data structures make the methods almost unusable in the frontend.

    public List<ExperimentResult> updateQualityOfExperimentResultsAndWrite(List<ExperimentResult> experimentResults, double medianNoiseIntensity, double isolationWindowWidth, double isolationWindowShift, Path outDir) throws IOException {
        List<Ms2Experiment> experiments =  new ArrayList<>();
        for (ExperimentResult experimentResult : experimentResults) {
            experiments.add(experimentResult.getExperiment());
        }
        Ms2Run dataset = estimateQuality(experiments, medianNoiseIntensity, isolationWindowWidth, isolationWindowShift);
        List<ExperimentResult> resultsWithUpdatedExperiments = new ArrayList<>();
        int i = 0;
        for (Ms2Experiment experiment : dataset) {
            ExperimentResult result = experimentResults.get(i++);
            ExperimentResult updatedResult = new ExperimentResult(experiment, result.getResults(), result.getExperiment().getSource(), result.getExperiment().getName());
            resultsWithUpdatedExperiments.add(updatedResult);
        }

        //this should update the input experiments, so that the updated information can be written in the next step;
        resultsWithUpdatedExperiments = updateQualityByExplainedPeaks(resultsWithUpdatedExperiments);

        if (outDir!=null) {
            writeQualitySummary(dataset, outDir);
        }


        return resultsWithUpdatedExperiments;
    }

    public Ms2Run updateQualityAndWrite(List<Ms2Experiment> experiments, double medianNoiseIntensity, double isolationWindowWidth, double isolationWindowShift, Path outDir) throws IOException {

        Ms2Run dataset = estimateQuality(experiments, medianNoiseIntensity, isolationWindowWidth, isolationWindowShift);

        if (outDir!=null) {
            writeQualitySummary(dataset, outDir);
        }

        return dataset;
    }

    protected Ms2Run estimateQuality(List<Ms2Experiment> experiments, double medianNoiseIntensity, double isolationWindowWidth, double isolationWindowShift) throws IOException {
        Ms2Run dataset = new MutableMs2Run(experiments, "default", Double.NaN, (new Sirius("default")).getMs2Analyzer().getDefaultProfile());
        Ms2RunPreprocessor preprocessor = new Ms2RunPreprocessor(true);

        if (medianNoiseIntensity>0) {
            DatasetStatistics datasetStatistics = preprocessor.makeStatistics(dataset);
            double minMs1Intensity = datasetStatistics.getMinMs1Intensity();
            double maxMs1Intensity = datasetStatistics.getMaxMs1Intensity();
            double minMs2Intensity = datasetStatistics.getMinMs2Intensity();
            double maxMs2Intensity = datasetStatistics.getMaxMs2Intensity();
            double minMs2NoiseIntensity = medianNoiseIntensity;
            double maxMs2NoiseIntensity = medianNoiseIntensity;
            double meanMs2NoiseIntensity = medianNoiseIntensity;
            double medianMs2NoiseIntensity = medianNoiseIntensity;
            FixedDatasetStatistics fixedDatasetStatistics = new FixedDatasetStatistics(minMs1Intensity, maxMs1Intensity, minMs2Intensity, maxMs2Intensity, minMs2NoiseIntensity, maxMs2NoiseIntensity, meanMs2NoiseIntensity, medianMs2NoiseIntensity);
            ((MutableMs2Run) dataset).setDatasetStatistics(fixedDatasetStatistics);
        }

        List<QualityAnnotator> qualityAnnotators = new ArrayList<>();
        qualityAnnotators.add(new NoMs1PeakAnnotator(Ms2RunPreprocessor.FIND_MS1_PEAK_DEVIATION));
        qualityAnnotators.add(new FewPeaksAnnotator(Ms2RunPreprocessor.MIN_NUMBER_OF_PEAKS));
        qualityAnnotators.add(new LowIntensityAnnotator(Ms2RunPreprocessor.FIND_MS1_PEAK_DEVIATION, 0.01, Double.NaN));
//        qualityAnnotators.add(new NotMonoisotopicAnnotatorUsingIPA(Ms2RunPreprocessor.FIND_MS1_PEAK_DEVIATION));
        double max2ndMostIntenseRatio = 0.33;
        double maxSummedIntensitiesRatio = 1.0;
        qualityAnnotators.add(new ChimericAnnotator(Ms2RunPreprocessor.FIND_MS1_PEAK_DEVIATION, max2ndMostIntenseRatio, maxSummedIntensitiesRatio));

        preprocessor.setQualityAnnotators(qualityAnnotators);


        if (isolationWindowWidth>0){
            double right = isolationWindowWidth/2d+isolationWindowShift;
            double left = -isolationWindowWidth/2d+isolationWindowShift;
            ((MutableMs2Run) dataset).setIsolationWindow(new SimpleRectangularIsolationWindow(left, right));
        }

        dataset = preprocessor.preprocess(dataset);

        return dataset;
    }


    protected List<ExperimentResult> updateQualityByExplainedPeaks(List<ExperimentResult> experimentResults) {
        List<ExperimentResult> newExperimentResults = new ArrayList<>();
        for (ExperimentResult result : experimentResults) {
            List<FTree> trees = new ArrayList<>();
            Ms2Experiment experiment  = result.getExperiment();
            for (IdentificationResult identificationResult : result.getResults()) {
//                trees.add(identificationResult.getRawTree());
                trees.add(identificationResult.getResolvedTree()); //todo use rawTree or resolvedTree?!
            }


            if (!atLeastOneTreeExplainsSomeIntensity(trees, 0.5)){
                CompoundQuality.setProperty(experiment, SpectrumProperty.PoorlyExplained);
            }
            if (!atLeastOneTreeExplainsSomePeaks(trees, 5)){
                CompoundQuality.setProperty(experiment, SpectrumProperty.PoorlyExplained);
            }


            newExperimentResults.add(new ExperimentResult(experiment, result.getResults()));
        }
        return newExperimentResults;
    }


    public static boolean atLeastOneTreeExplainsSomeIntensity(List<FTree> trees, double threshold){
        for (FTree tree : trees) {
            final double intensity = tree.getAnnotationOrThrow(TreeScoring.class).getExplainedIntensity();
            if (intensity>threshold) return true;
        }
        return false;
    }

    public static boolean atLeastOneTreeExplainsSomePeaks(List<FTree> trees, int threshold){
        for (FTree tree : trees) {
            if (tree.numberOfVertices()>=threshold) return true;
        }
        return false;
    }

    public void writeQualitySummary(Ms2Run dataset, Path outputDir) throws IOException {
        Ms2RunPreprocessor preprocessor = new Ms2RunPreprocessor(false);
        Path qualityPath = outputDir.resolve("spectra_quality.csv");
        SpectrumProperty[] usedProperties = CompoundQuality.getUsedProperties(dataset);
        preprocessor.writeExperimentInfos(dataset, qualityPath, usedProperties);


        dataset.getIsolationWindow().writeIntensityRatiosToCsv(dataset, outputDir.resolve("isolation_window_intensities.csv"));

        Path summary = outputDir.resolve("data_summary.csv");
        System.out.println("write summary");
        preprocessor.writeDatasetSummary(dataset, summary);
        System.out.println("writing summary ended");

    }

}
