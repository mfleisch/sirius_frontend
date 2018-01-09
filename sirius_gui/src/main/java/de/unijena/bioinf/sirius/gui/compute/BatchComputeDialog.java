/*
 *  This file is part of the SIRIUS library for analyzing MS and MS/MS data
 *
 *  Copyright (C) 2013-2015 Kai Dührkop
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with SIRIUS.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.unijena.bioinf.sirius.gui.compute;

import de.unijena.bioinf.ChemistryBase.chem.Element;
import de.unijena.bioinf.ChemistryBase.chem.FormulaConstraints;
import de.unijena.bioinf.ChemistryBase.ms.MutableMs2Experiment;
import de.unijena.bioinf.ChemistryBase.ms.PossibleAdducts;
import de.unijena.bioinf.ChemistryBase.ms.PossibleIonModes;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.tree.TreeBuilder;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.tree.TreeBuilderFactory;
import de.unijena.bioinf.IsotopePatternAnalysis.prediction.ElementPredictor;
import de.unijena.bioinf.fingerid.FingerIDComputationPanel;
import de.unijena.bioinf.fingerid.db.SearchableDatabase;
import de.unijena.bioinf.sirius.Sirius;
import de.unijena.bioinf.sirius.core.ApplicationCore;
import de.unijena.bioinf.sirius.gui.dialogs.ErrorReportDialog;
import de.unijena.bioinf.sirius.gui.dialogs.ExceptionDialog;
import de.unijena.bioinf.sirius.gui.dialogs.QuestionDialog;
import de.unijena.bioinf.sirius.gui.mainframe.MainFrame;
import de.unijena.bioinf.sirius.gui.structure.ComputingStatus;
import de.unijena.bioinf.sirius.gui.structure.ExperimentContainer;
import de.unijena.bioinf.sirius.gui.structure.ReturnValue;
import de.unijena.bioinf.sirius.gui.utils.ExperiemtEditPanel;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.*;
import java.util.List;

public class BatchComputeDialog extends JDialog implements ActionListener {

    private JButton compute;
    private JButton abort;

    private JCheckBox recompute;

    private JButton elementAutoDetect = null;

    private ElementsPanel elementPanel;
    private ExperiemtEditPanel editPanel;
    private SearchProfilePanel searchProfilePanel;
    private FingerIDComputationPanel csiOptions;

    private MainFrame owner;
    List<ExperimentContainer> compoundsToProcess;

    private Sirius sirius;
    private boolean success;

    public BatchComputeDialog(MainFrame owner, List<ExperimentContainer> compoundsToProcess) {
        super(owner, "compute", true);
        this.owner = owner;
        this.compoundsToProcess = compoundsToProcess;
        this.success = false;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        this.setLayout(new BorderLayout());

        Box mainPanel = Box.createVerticalBox();
        this.add(mainPanel, BorderLayout.CENTER);
        //mainpanel done


        this.sirius = new Sirius();
        ElementPredictor elementPredictor = sirius.getElementPrediction();
        List<Element> detectableElements = new ArrayList<>();
        for (Element element : elementPredictor.getChemicalAlphabet().getElements()) {
            if (elementPredictor.isPredictable(element)) detectableElements.add(element);
        }


        if (compoundsToProcess.size() > 1) {
            ///////////////////Multi Element//////////////////////
            elementPanel = new ElementsPanel(this, 4, detectableElements);
            mainPanel.add(elementPanel);
            boolean enableFallback = hasCompoundWithUnknownIonization();
            searchProfilePanel = new SearchProfilePanel(this, enableFallback);
            /////////////////////////////////////////////
        } else {
            initSingleExperimentDialog(mainPanel, detectableElements);
            searchProfilePanel = new SearchProfilePanel(this, compoundsToProcess.get(0).getIonization());
        }


        mainPanel.add(searchProfilePanel);
        searchProfilePanel.formulaCombobox.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                SearchableDatabase source = searchProfilePanel.getFormulaSource();
                enableElementSelection(source == null);
                if (!csiOptions.isEnabled()) csiOptions.dbSelectionOptions.setDb(source);
            }
        });

        JPanel stack = new JPanel();
        stack.setLayout(new BorderLayout());
        stack.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "CSI:FingerId - Structure Elucidation"));

        csiOptions = new FingerIDComputationPanel(owner.getCsiFingerId().getAvailableDatabases(), searchProfilePanel.ionizationCB, true, true);
        if (!csiOptions.isEnabled()) csiOptions.dbSelectionOptions.setDb(searchProfilePanel.getFormulaSource());
        csiOptions.setMaximumSize(csiOptions.getPreferredSize());

        stack.add(csiOptions, BorderLayout.CENTER);
        mainPanel.add(stack);


        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.LINE_AXIS));

        JPanel lsouthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        recompute = new JCheckBox("Recompute already computed compounds?", false);
        recompute.setToolTipText("If checked, all selected compounds will be computed. Already computed ones we be recomputed.");
        lsouthPanel.add(recompute);

        //checkConnectionToUrl by default when just one experiment is selected
        if (compoundsToProcess.size() == 1) recompute.setSelected(true);

        JPanel rsouthPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
        compute = new JButton("Compute");
        compute.addActionListener(this);
        abort = new JButton("Abort");
        abort.addActionListener(this);
        rsouthPanel.add(compute);
        rsouthPanel.add(abort);

        southPanel.add(lsouthPanel);
        southPanel.add(rsouthPanel);

        this.add(southPanel, BorderLayout.SOUTH);

        {
            InputMap inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            KeyStroke enterKey = KeyStroke.getKeyStroke("ENTER");
            KeyStroke escKey = KeyStroke.getKeyStroke("ESCAPE");
            String enterAction = "compute";
            String escAction = "abort";
            inputMap.put(enterKey, enterAction);
            inputMap.put(escKey, escAction);
            getRootPane().getActionMap().put(enterAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    startComputing();
                }
            });
            getRootPane().getActionMap().put(escAction, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    abortComputing();
                }
            });
        }


        this.pack();
        this.setResizable(false);
        setLocationRelativeTo(getParent());
        this.setVisible(true);

    }

    public void enableElementSelection(boolean enabled) {
        elementPanel.enableElementSelection(enabled);
        if (elementAutoDetect != null)
            elementAutoDetect.setEnabled(enabled);
    }


    private boolean hasCompoundWithUnknownIonization() {
        Iterator<ExperimentContainer> compounds = this.compoundsToProcess.iterator();
        while (compounds.hasNext()) {
            final ExperimentContainer ec = compounds.next();
            if (ec.getIonization() == null || ec.getIonization().isIonizationUnknown()) {
                return true;
            }
        }
        return false;
    }


    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == abort) {
            this.dispose();
        } else if (e.getSource() == this.compute) {
            startComputing();
        } else if (e.getSource() == elementAutoDetect) {
            String notWorkingMessage = "Element detection requires MS1 spectrum with isotope pattern.";
            ExperimentContainer ec = compoundsToProcess.get(0);
            if (!ec.getMs1Spectra().isEmpty() || ec.getMergedMs1Spectrum() != null) {
                MutableMs2Experiment exp = ec.getMs2Experiment();

                ElementPredictor predictor = sirius.getElementPrediction();
                final FormulaConstraints c = sirius.predictElementsFromMs1(exp);
                if (c != null) {
                    for (Element element : c.getChemicalAlphabet()) {
                        if (!predictor.isPredictable(element)) {
                            c.setLowerbound(element, 0);
                            c.setUpperbound(element, 0);
                        }
                    }
                    elementPanel.setSelectedElements(c);
                } else {
                    new ExceptionDialog(this, notWorkingMessage);
                }
            } else {
                new ExceptionDialog(this, notWorkingMessage);
            }
        }
    }

    private void abortComputing() {
        this.dispose();
    }

    private void startComputing() {
        if (recompute.isSelected()) {
            final String dontAskProperty = "de.unijena.bioinf.sirius.dontAsk.recompute";
            Properties properties = ApplicationCore.SIRIUS_PROPERTIES_FILE.getCopyOfPersistentProperties();

            ReturnValue value;
            if (Boolean.parseBoolean(properties.getProperty(dontAskProperty, "false")) || this.compoundsToProcess.size() == 1) {
                value = ReturnValue.Success;
            } else {
                QuestionDialog questionDialog = new QuestionDialog(this, "<html><body>Do you really want to recompute already computed experiments? <br> All existing results will be lost!</body></html>", dontAskProperty);
                value = questionDialog.getReturnValue();
            }

            //reset status of uncomputed values
            if (value == ReturnValue.Success) {
                final Iterator<ExperimentContainer> compounds = this.compoundsToProcess.iterator();
                while (compounds.hasNext()) {
                    final ExperimentContainer ec = compounds.next();
                    ec.setComputeState(ComputingStatus.UNCOMPUTED);
                }
            }
        }


        String instrument = searchProfilePanel.getInstrument().profile;

        SearchableDatabase searchableDatabase = searchProfilePanel.getFormulaSource();

        FormulaConstraints constraints = elementPanel.getElementConstraints();
        List<Element> elementsToAutoDetect = Collections.EMPTY_LIST;
        if (elementPanel.individualAutoDetect)
            elementsToAutoDetect = elementPanel.getElementsToAutoDetect();

        final double ppm = searchProfilePanel.getPpm();
        final int candidates = searchProfilePanel.getNumberOfCandidates();

        // CHECK ILP SOLVER
        TreeBuilder builder = new Sirius().getMs2Analyzer().getTreeBuilder();
        if (builder == null) {
            String noILPSolver = "Could not load a valid TreeBuilder (ILP solvers) " + Arrays.toString(TreeBuilderFactory.getBuilderPriorities()) + ". Please read the installation instructions.";
            LoggerFactory.getLogger(this.getClass()).error(noILPSolver);
            new ErrorReportDialog(this, noILPSolver);
            dispose();
            return;
        }
        LoggerFactory.getLogger(this.getClass()).info("Compute trees using " + builder);

        //entspricht setup() Methode
        final BackgroundComputation bgc = owner.getBackgroundComputation();
        final Iterator<ExperimentContainer> compounds = this.compoundsToProcess.iterator();
        final ArrayList<BackgroundComputation.Task> tasks = new ArrayList<>();
        while (compounds.hasNext()) {
            final ExperimentContainer ec = compounds.next();
            if (ec.isUncomputed()) {
                MutableMs2Experiment exp = applySettingsAndGet(ec);
                FormulaConstraints individualConstraints = new FormulaConstraints(constraints);

                if (!elementsToAutoDetect.isEmpty() && !ec.getMs1Spectra().isEmpty()) {
                    FormulaConstraints autoConstraints = sirius.predictElementsFromMs1(exp);
                    if (autoConstraints != null) {
                        ElementPredictor predictor = sirius.getElementPrediction();
                        for (Element element : elementsToAutoDetect) {
                            if (predictor.isPredictable(element)) {
                                individualConstraints.setUpperbound(element, autoConstraints.getUpperbound(element));
                            }
                        }
                    }
                }
                final BackgroundComputation.Task task = new BackgroundComputation.Task(instrument, ec, individualConstraints, ppm, candidates, searchableDatabase, searchProfilePanel.hasIsotopesEnabled(), csiOptions.isCSISelected(), csiOptions.dbSelectionOptions.getDb(), searchProfilePanel.restrictToOrganics());
                tasks.add(task);
            }
        }
        bgc.addAll(tasks);
        dispose();
    }

    public boolean isSuccessful() {
        return this.success;
    }

    public void initSingleExperimentDialog(Box mainPanel, List<Element> detectableElements) {
        ExperimentContainer ec = compoundsToProcess.get(0);
        editPanel = new ExperiemtEditPanel();
        editPanel.setBorder(BorderFactory.createEtchedBorder());
        editPanel.nameTF.setText(ec.getName());
        editPanel.ionizationCB.setSelectedItem(ec.getIonization().getIonization().getName());
        editPanel.precursorSelection.setData(ec.getMs1Spectra(), ec.getMs2Spectra(), ec.getIonMass());
        mainPanel.add(editPanel, BorderLayout.NORTH);

        editPanel.ionizationCB.addActionListener(e -> searchProfilePanel.refreshPossibleIonizations(editPanel.getSelectedIonization()));

        /////////////Solo Element//////////////////////
        elementPanel = new ElementsPanel(this, 4);
        mainPanel.add(elementPanel);

        StringBuilder builder = new StringBuilder();
        builder.append("Auto detectable element are: ");
        for (int i = 0; i < detectableElements.size(); i++) {
            if (i != 0) builder.append(", ");
            builder.append(detectableElements.get(i).getSymbol());
        }
        elementAutoDetect = new JButton("Auto detect");
        elementAutoDetect.setToolTipText(builder.toString());
        elementAutoDetect.addActionListener(this);
        elementAutoDetect.setEnabled(true);
        elementPanel.lowerPanel.add(elementAutoDetect);
        /////////////////////////////////////////////
    }


    private MutableMs2Experiment applySettingsAndGet(ExperimentContainer ec) {
        if (editPanel != null) {
            final Double ionMass = editPanel.getSelectedIonMass();
            if (ionMass != null)
                ec.setIonMass(ionMass);
            ec.setName(editPanel.getExperiementName());
            ec.setIonization(editPanel.getSelectedIonization());
        }

        final MutableMs2Experiment exp = ec.getMs2Experiment();
        exp.setAnnotation(PossibleIonModes.class, searchProfilePanel.getPossibleIonModes());
        exp.setAnnotation(PossibleAdducts.class, csiOptions.getPossibleAdducts());
        return exp;
    }
}