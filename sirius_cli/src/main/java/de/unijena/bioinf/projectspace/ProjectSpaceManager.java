/*
 *  This file is part of the SIRIUS Software for analyzing MS and MS/MS data
 *
 *  Copyright (C) 2013-2020 Kai Dührkop, Markus Fleischauer, Marcus Ludwig, Martin A. Hoffman, Fleming Kretschmer, Marvin Meusel and Sebastian Böcker,
 *  Chair of Bioinformatics, Friedrich-Schiller University.
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Affero General Public License
 *  as published by the Free Software Foundation; either
 *  version 3 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License along with SIRIUS.  If not, see <https://www.gnu.org/licenses/agpl-3.0.txt>
 */

package de.unijena.bioinf.projectspace;

import de.unijena.bioinf.ChemistryBase.algorithm.scoring.FormulaScore;
import de.unijena.bioinf.ChemistryBase.ms.Ms2Experiment;
import de.unijena.bioinf.ChemistryBase.ms.ft.FTree;
import de.unijena.bioinf.ChemistryBase.ms.lcms.LCMSPeakInformation;
import de.unijena.bioinf.GibbsSampling.ZodiacScore;
import de.unijena.bioinf.babelms.projectspace.PassatuttoSerializer;
import de.unijena.bioinf.canopus.CanopusResult;
import de.unijena.bioinf.fingerid.ConfidenceScore;
import de.unijena.bioinf.fingerid.FingerprintResult;
import de.unijena.bioinf.fingerid.blast.FBCandidateFingerprints;
import de.unijena.bioinf.fingerid.blast.FBCandidates;
import de.unijena.bioinf.fingerid.blast.TopCSIScore;
import de.unijena.bioinf.fingerid.predictor_types.PredictorType;
import de.unijena.bioinf.ms.annotations.DataAnnotation;
import de.unijena.bioinf.ms.frontend.core.ApplicationCore;
import de.unijena.bioinf.ms.properties.PropertyManager;
import de.unijena.bioinf.ms.rest.model.canopus.CanopusData;
import de.unijena.bioinf.ms.rest.model.fingerid.FingerIdData;
import de.unijena.bioinf.passatutto.Decoy;
import de.unijena.bioinf.projectspace.canopus.CanopusDataProperty;
import de.unijena.bioinf.projectspace.canopus.CanopusDataSerializer;
import de.unijena.bioinf.projectspace.canopus.CanopusSerializer;
import de.unijena.bioinf.projectspace.fingerid.*;
import de.unijena.bioinf.projectspace.sirius.*;
import de.unijena.bioinf.projectspace.summaries.CanopusSummaryWriter;
import de.unijena.bioinf.projectspace.summaries.FormulaSummaryWriter;
import de.unijena.bioinf.projectspace.summaries.StructureSummaryWriter;
import de.unijena.bioinf.projectspace.summaries.mztab.MztabMExporter;
import de.unijena.bioinf.sirius.scores.IsotopeScore;
import de.unijena.bioinf.sirius.scores.SiriusScore;
import de.unijena.bioinf.sirius.scores.TreeScore;
import de.unijena.bioinf.utils.NetUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Manage the project space.
 * e.g. iteration on Instance level.
 * maybe some type of caching?
 */
public class ProjectSpaceManager implements Iterable<Instance> {
    @NotNull public static Supplier<ProjectSpaceConfiguration> DEFAULT_CONFIG = () -> {
        final ProjectSpaceConfiguration config = new ProjectSpaceConfiguration();
        //configure ProjectSpaceProperties
        config.defineProjectSpaceProperty(FilenameFormatter.PSProperty.class, new FilenameFormatter.PSPropertySerializer());
        //configure compound container
        config.registerContainer(CompoundContainer.class, new CompoundContainerSerializer());
        config.registerComponent(CompoundContainer.class, ProjectSpaceConfig.class, new ProjectSpaceConfigSerializer());
        config.registerComponent(CompoundContainer.class, Ms2Experiment.class, new MsExperimentSerializer());
        //configure formula result
        config.registerContainer(FormulaResult.class, new FormulaResultSerializer());
        config.registerComponent(FormulaResult.class, FTree.class, new TreeSerializer());
        config.registerComponent(FormulaResult.class, FormulaScoring.class, new FormulaScoringSerializer());
        //pssatuto components
        config.registerComponent(FormulaResult.class, Decoy.class, new PassatuttoSerializer());
        //fingerid components
        config.defineProjectSpaceProperty(FingerIdDataProperty.class, new FingerIdDataSerializer());
        config.registerComponent(FormulaResult.class, FingerprintResult.class, new FingerprintSerializer());
        config.registerComponent(FormulaResult.class, FBCandidates.class, new FBCandidatesSerializer());
        config.registerComponent(FormulaResult.class, FBCandidateFingerprints.class, new FBCandidateFingerprintSerializer());
        //canopus
        config.defineProjectSpaceProperty(CanopusDataProperty.class, new CanopusDataSerializer());
        config.registerComponent(FormulaResult.class, CanopusResult.class, new CanopusSerializer());

        config.registerComponent(CompoundContainer.class, LCMSPeakInformation.class, new LCMSPeakSerializer());

        return config;
    };

    private final SiriusProjectSpace space;
    public final Function<Ms2Experiment, String> nameFormatter;
    public final BiFunction<Integer, String, String> namingScheme;
    private Predicate<CompoundContainerId> compoundIdFilter;
    protected final InstanceFactory<?> instFac;


    public ProjectSpaceManager(@NotNull SiriusProjectSpace space, @NotNull InstanceFactory<?> factory, @Nullable Function<Ms2Experiment, String> formatter) {
        this.space = space;
        this.instFac = factory;
        this.nameFormatter = space.getProjectSpaceProperty(FilenameFormatter.PSProperty.class).map(p -> (Function<Ms2Experiment, String>) new StandardMSFilenameFormatter(p.formatExpression))
                .orElseGet(() -> {
                    Function<Ms2Experiment, String> f = (formatter != null) ? formatter : new StandardMSFilenameFormatter();
                    if (f instanceof FilenameFormatter)
                        space.setProjectSpaceProperty(FilenameFormatter.PSProperty.class, new FilenameFormatter.PSProperty((FilenameFormatter) f));
                    return f;
                });

        this.namingScheme = (idx, name) -> idx + "_" + name;
    }

    public SiriusProjectSpace projectSpace() {
        return space;
    }


    @NotNull
    public Instance newCompoundWithUniqueId(Ms2Experiment inputExperiment) {
        final String name = nameFormatter.apply(inputExperiment);
        final CompoundContainer container = projectSpace().newCompoundWithUniqueId(name, (idx) -> namingScheme.apply(idx, name), inputExperiment).orElseThrow(() -> new RuntimeException("Could not create an project space ID for the Instance"));
        return instFac.create(container, this);
    }

    public Predicate<CompoundContainerId> getCompoundIdFilter() {
        return compoundIdFilter;
    }

    public void setCompoundIdFilter(Predicate<CompoundContainerId> compoundFilter) {
        this.compoundIdFilter = compoundFilter;
    }

    @SafeVarargs
    public final Instance newInstanceFromCompound(CompoundContainerId id, Class<? extends DataAnnotation>... components) {
        try {
            CompoundContainer c = projectSpace().getCompound(id, components);
            return instFac.create(c, this);
        } catch (IOException e) {
            LoggerFactory.getLogger(Instance.class).error("Could not create read Input Experiment from Project Space.");
            throw new RuntimeException("Could not create read Input Experiment from Project Space.", e);
        }
    }

    public <T extends ProjectSpaceProperty> Optional<T> getProjectSpaceProperty(Class<T> key) {
        return projectSpace().getProjectSpaceProperty(key);
    }

    public <T extends ProjectSpaceProperty> T setProjectSpaceProperty(Class<T> key, T value) {
        if (PosNegFpProperty.class.isAssignableFrom(key))
            synchronized (dataCompatibilityCache) {
                dataCompatibilityCache.remove(key);
                return projectSpace().setProjectSpaceProperty(key, value);
            }
        else
            return projectSpace().setProjectSpaceProperty(key, value);
    }

    public <T extends ProjectSpaceProperty> T deleteProjectSpaceProperty(Class<T> key) {
        if (PosNegFpProperty.class.isAssignableFrom(key))
            synchronized (dataCompatibilityCache) {
                dataCompatibilityCache.remove(key);
                return projectSpace().deleteProjectSpaceProperty(key);
            }
        else
            return projectSpace().deleteProjectSpaceProperty(key);
    }

    @NotNull
    public Iterator<Instance> filteredIterator(@Nullable Predicate<CompoundContainerId> cidFilter, @Nullable final Predicate<CompoundContainer> compoundFilter) {
        if (compoundFilter == null && cidFilter == null)
            return iterator();
        final Predicate<CompoundContainerId> cidF = (cidFilter != null && compoundFilter != null)
                ? (cid) -> cidFilter.test(cid) && this.compoundIdFilter.test(cid)
                : cidFilter == null ? this.compoundIdFilter : cidFilter;
        return makeInstanceIterator(space.filteredCompoundIterator(cidF, compoundFilter, Ms2Experiment.class));
    }


    @NotNull
    @Override
    public Iterator<Instance> iterator() {
        if (compoundIdFilter != null)
            return filteredIterator(compoundIdFilter, null);
        return makeInstanceIterator(space.compoundIterator(Ms2Experiment.class));
    }

    private Iterator<Instance> makeInstanceIterator(@NotNull final Iterator<CompoundContainer> compoundIt) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return compoundIt.hasNext();
            }

            @Override
            public Instance next() {
                final CompoundContainer c = compoundIt.next();
                if (c == null) return null;
                return instFac.create(c, ProjectSpaceManager.this);
            }
        };
    }

    public int size() {
        return space.size();
    }

    public boolean containsCompound(String dirName) {
        return space.containsCompound(dirName);
    }

    public boolean containsCompound(CompoundContainerId id) {
        return space.containsCompound(id);
    }


    public void updateSummaries(Summarizer... summarizers) throws IOException {
        space.updateSummaries(summarizers);
    }

    public void close() throws IOException {
        space.close();
    }

    public static void writeIncompatibleLog() {
        LoggerFactory.getLogger(ProjectSpaceManager.class).warn("INCOMPATIBLE INPUT: The Fingerprint version of your Project ist incompatible to the one used by this SIRIUS version (outdated)." +
                " The project can be Converted using `--update-fingerprint-version`." +
                " WARNING: This will delete all Fingerprint related results like CSI:FingerID and CANOPUS.");
    }

    private final Map<Class<? extends PosNegFpProperty<?, ?>>, Boolean> dataCompatibilityCache = new HashMap<>();

    /**
     * This checks whether the data files are compatible with them on the server. Since have had versions of the PS with
     * incomplete data files it also load missing files from the server but only if the existing one are compatible.
     * <p>
     * Results are cached!
     *
     * @param interrupted  Tell the waiting job how it can check if it was interrupted
     * @return true if data files are  NOT incompatible with the Server version (compatible or not existent)
     * @throws TimeoutException     if server request times out
     * @throws InterruptedException if waiting for server request is interrupted
     */
    public boolean checkAndFixDataFiles(NetUtils.InterruptionCheck interrupted) throws TimeoutException, InterruptedException {
        if (PropertyManager.getBoolean("de.unijena.bioinf.sirius.project-check", null, false))
            return true;

        synchronized (dataCompatibilityCache) {
            try {
                if (!dataCompatibilityCache.containsKey(FingerIdDataProperty.class)) {
                    final FingerIdDataProperty fd = getProjectSpaceProperty(FingerIdDataProperty.class).orElse(null);
                    if (fd != null) {
                        dataCompatibilityCache.put(FingerIdDataProperty.class, true);
                        final FingerIdData pos = NetUtils.tryAndWait(() -> ApplicationCore.WEB_API.getFingerIdData(PredictorType.CSI_FINGERID_POSITIVE), interrupted);
                        final FingerIdData neg = NetUtils.tryAndWait(() -> ApplicationCore.WEB_API.getFingerIdData(PredictorType.CSI_FINGERID_NEGATIVE), interrupted);
                        if (fd.getPositive() != null) {
                            if (!fd.getPositive().compatible(pos)) {
                                dataCompatibilityCache.put(FingerIdDataProperty.class, false);
                            } else if (fd.getNegative() == null) {
                                LoggerFactory.getLogger(InstanceImporter.class).warn("Negative FingerIdData missing in project. Try to repair by reloading from webservice.");
                                setProjectSpaceProperty(FingerIdDataProperty.class,
                                        new FingerIdDataProperty(fd.getPositive(), neg));
                            }
                        }

                        if (fd.getNegative() != null) {
                            if (!fd.getNegative().compatible(neg)) {
                                dataCompatibilityCache.put(FingerIdDataProperty.class, false);
                            } else if (fd.getPositive() == null) {
                                LoggerFactory.getLogger(InstanceImporter.class).warn("Positive FingerIdData missing in project. Try to repair by reloading from webservice.");
                                setProjectSpaceProperty(FingerIdDataProperty.class,
                                        new FingerIdDataProperty(pos, fd.getNegative()));
                            }
                        }
                    }
                }

                if (!dataCompatibilityCache.containsKey(CanopusDataProperty.class)) {
                    final CanopusDataProperty cd = getProjectSpaceProperty(CanopusDataProperty.class).orElse(null);
                    if (cd != null) {
                        dataCompatibilityCache.put(CanopusDataProperty.class, true);
                        final CanopusData pos = NetUtils.tryAndWait(() -> ApplicationCore.WEB_API.getCanopusdData(PredictorType.CSI_FINGERID_POSITIVE), interrupted);
                        final CanopusData neg = NetUtils.tryAndWait(() -> ApplicationCore.WEB_API.getCanopusdData(PredictorType.CSI_FINGERID_NEGATIVE), interrupted);
                        if (cd.getPositive() != null) {
                            if (!cd.getPositive().compatible(pos)) {
                                dataCompatibilityCache.put(CanopusDataProperty.class, false);
                            } else if (cd.getNegative() == null) {
                                LoggerFactory.getLogger(InstanceImporter.class).warn("Negative CanopusData missing in project. Try to repair by reloading from webservice.");
                                setProjectSpaceProperty(CanopusDataProperty.class,
                                        new CanopusDataProperty(cd.getPositive(), neg));
                            }
                        }

                        if (cd.getNegative() != null) {
                            if (!cd.getNegative().compatible(neg)) {
                                dataCompatibilityCache.put(CanopusDataProperty.class, false);
                            } else if (cd.getPositive() == null) {
                                LoggerFactory.getLogger(InstanceImporter.class).warn("Positive CanopusData missing in project. Try to repair by reloading from webservice.");
                                setProjectSpaceProperty(CanopusDataProperty.class,
                                        new CanopusDataProperty(neg, cd.getNegative()));
                            }
                        }
                    }
                }
                boolean out = dataCompatibilityCache.values().stream().reduce((a, b) -> a && b).orElse(true);
                if (!out) writeIncompatibleLog();
                return out;
            } catch (Exception e) {
                dataCompatibilityCache.clear();
                LoggerFactory.getLogger(getClass()).warn("Could not retrieve FingerprintData from server! \n" + e.getMessage());
                throw e;
            }
        }
    }

    //region static helper
    public static Summarizer[] defaultSummarizer() {
        return new Summarizer[]{
                new FormulaSummaryWriter(),
                new StructureSummaryWriter(),
                new CanopusSummaryWriter(),
                new MztabMExporter()
        };
    }

    public static List<Class<? extends FormulaScore>> scorePriorities() {
        final LinkedList<Class<? extends FormulaScore>> list = new LinkedList<>();
        list.add(ConfidenceScore.class);
        list.add(TopCSIScore.class);
        list.add(ZodiacScore.class);
        list.add(SiriusScore.class);
        list.add(TreeScore.class);
        list.add(IsotopeScore.class);
        return list;
    }


    public static ProjectSpaceConfiguration newDefaultConfig(){
        return DEFAULT_CONFIG.get();
    }
    //end region
}
