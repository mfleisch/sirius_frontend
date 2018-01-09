package de.unijena.bioinf.sirius.gui.load;

import ca.odell.glazedlists.BasicEventList;
import de.unijena.bioinf.ChemistryBase.chem.PrecursorIonType;
import de.unijena.bioinf.ChemistryBase.ms.*;
import de.unijena.bioinf.ChemistryBase.ms.utils.SimpleSpectrum;
import de.unijena.bioinf.fingerid.storage.ConfigStorage;
import de.unijena.bioinf.myxo.io.spectrum.CSVFormatReader;
import de.unijena.bioinf.sirius.gui.dialogs.ErrorListDialog;
import de.unijena.bioinf.sirius.gui.dialogs.ExceptionDialog;
import de.unijena.bioinf.sirius.gui.filefilter.SupportedDataFormatsFilter;
import de.unijena.bioinf.sirius.gui.mainframe.BatchImportDialog;
import de.unijena.bioinf.sirius.gui.mainframe.FileImportDialog;
import de.unijena.bioinf.sirius.gui.structure.CSVToSpectrumConverter;
import de.unijena.bioinf.sirius.gui.structure.ExperimentContainer;
import de.unijena.bioinf.sirius.gui.structure.ReturnValue;
import de.unijena.bioinf.sirius.gui.structure.SpectrumContainer;
import gnu.trove.list.array.TDoubleArrayList;

import javax.swing.*;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class LoadController implements LoadDialogListener {
    private final ConfigStorage config;
    private final JFrame owner;
    private DefaultLoadDialog loadDialog;

    private ExperimentContainer expToModify;
    private URL source;

    private final BasicEventList<SpectrumContainer> spectra;


    public LoadController(JFrame owner, ExperimentContainer exp, ConfigStorage config) {
        this.owner = owner;
        this.config = config;

        expToModify = exp;

        if (expToModify != null) {
            spectra = new BasicEventList<>(expToModify.getMs1Spectra().size() + expToModify.getMs2Spectra().size());
            loadDialog = new DefaultLoadDialog(owner, spectra);


            loadDialog.ionizationChanged(exp.getIonization() != null ? exp.getIonization() : PrecursorIonType.unknown(1));

            loadDialog.experimentNameChanged(exp.getName());

            for (Spectrum<? extends Peak> spectrum : expToModify.getMs1Spectra()) {
                addToSpectra(spectrum);
            }

            for (Spectrum<? extends Peak> spectrum : expToModify.getMs2Spectra()) {
                addToSpectra(spectrum);
            }
        } else {
            spectra = new BasicEventList<>();
            loadDialog = new DefaultLoadDialog(owner, spectra);
            loadDialog.ionizationChanged(PrecursorIonType.unknown(1));
            loadDialog.experimentNameChanged("");
            source = null;
        }

        loadDialog.addLoadDialogListener(this);
    }

    public LoadController(JFrame owner, ConfigStorage config) {
        this(owner, null, config);
    }

    public void showDialog() {
        loadDialog.showDialog();
    }

    private void addToSpectra(Spectrum<?>... sps) {
        spectra.addAll(Arrays.stream(sps).map(SpectrumContainer::new).collect(Collectors.toList()));
    }

    @Override
    public void addSpectra() {
        JFileChooser chooser = new JFileChooser(config.getDefaultLoadDialogPath());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        chooser.addChoosableFileFilter(new SupportedDataFormatsFilter());
        chooser.setAcceptAllFileFilterUsed(false);
        int returnVal = chooser.showOpenDialog((JDialog) loadDialog);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File[] files = chooser.getSelectedFiles();
            //setzt Pfad
            config.setDefaultLoadDialogPath(files[0].getParentFile());

            //untersuche die Dateitypen und schaue ob CSV vorhanden, wenn vorhanden behandelte alle CSVs auf
            //gleiche Weise
            importSpectra(Arrays.asList(files));
        }
    }

    private void importSpectra(List<File> files) {
        FileImportDialog idi = new FileImportDialog(owner, files);
        importSpectra(idi.getCSVFiles(), idi.getMSFiles(), idi.getMGFFiles());
    }


    private void importSpectra(List<File> csvFiles, List<File> msFiles, List<File> mgfFiles) {
        List<String> errorStorage = new ArrayList<>();

        //csv import
        if (csvFiles.size() > 0) {
            CSVDialogReturnContainer cont = null;
            CSVFormatReader csvReader = new CSVFormatReader();

            HashMap<Integer, List<List<TDoubleArrayList>>> columnNumberToData = new HashMap<>();

            for (File file : csvFiles) {
                try {
                    List<TDoubleArrayList> data = csvReader.readCSV(file);
                    Integer key = data.get(0).size();
                    if (columnNumberToData.containsKey(key)) {
                        columnNumberToData.get(key).add(data);
                    } else {
                        List<List<TDoubleArrayList>> list = new ArrayList<>();
                        list.add(data);
                        columnNumberToData.put(key, list);
                    }
                } catch (Exception e) {
                    errorStorage.add(file.getName() + ": Invalid file format.");
                }
            }

            if (columnNumberToData.size() > 0) {
                for (Integer key : columnNumberToData.keySet()) {
                    List<List<TDoubleArrayList>> list = columnNumberToData.get(key);
                    if (list.size() == 1) {
                        CSVDialog diag = new CSVDialog((JDialog) loadDialog, list.get(0), false);
                        if (diag.getReturnValue() == ReturnValue.Success) {
                            cont = diag.getResults();
                            CSVToSpectrumConverter conv = new CSVToSpectrumConverter();
                            Spectrum<?> sp = conv.convertCSVToSpectrum(list.get(0), cont);
                            addToSpectra(sp);
                        } else {
                            return; //breche ab
                        }
                    } else {
                        CSVDialog diag = new CSVDialog((JDialog) loadDialog, list.get(0), true);
                        if (diag.getReturnValue() == ReturnValue.Success) {
                            cont = diag.getResults();
                            cont.setMaxEnergy(-1);
                            cont.setMinEnergy(-1);
                            cont.setMsLevel(2);

                            for (List<TDoubleArrayList> data : list) {
                                CSVToSpectrumConverter conv = new CSVToSpectrumConverter();
                                Spectrum<?> sp = conv.convertCSVToSpectrum(data, cont);
                                addToSpectra(sp);
                            }
                        }
                    }
                }
            }
        }

        BatchImportDialog batchImportDialog = new BatchImportDialog(loadDialog);
        batchImportDialog.start(msFiles, mgfFiles);
        errorStorage.addAll(batchImportDialog.getErrors());

        //todo backround?
        for (Ms2Experiment exp : batchImportDialog.getResults()) {
            importExperiment(exp);
        }


        if (errorStorage.size() > 1) {
            ErrorListDialog elDiag = new ErrorListDialog(this.owner, errorStorage);
        } else if (errorStorage.size() == 1) {
            ExceptionDialog eDiag = new ExceptionDialog(this.owner, errorStorage.get(0));
        }

    }

    //this imports an merges the experiments
    private void importExperiment(Ms2Experiment experiment) {
        source = experiment.getSource();

        if (loadDialog.getIonization().isIonizationUnknown() && experiment.getPrecursorIonType() != null && !experiment.getPrecursorIonType().isIonizationUnknown())
            loadDialog.ionizationChanged(experiment.getPrecursorIonType());

        final String name = loadDialog.getExperimentName();
        if (name == null || name.isEmpty())
            loadDialog.experimentNameChanged(experiment.getName());

        for (Spectrum<Peak> sp : experiment.getMs1Spectra()) {
            addToSpectra(sp);
        }

        for (Ms2Spectrum<Peak> sp : experiment.getMs2Spectra()) {
            addToSpectra(sp);
        }
    }

    public ExperimentContainer getExperiment() {
        return expToModify;
    }


    //todo maybe in backround, will freeze the gui for many spectra
    @Override
    public void removeSpectra(List<SpectrumContainer> sps) {
        spectra.removeAll(sps);

        if (spectra.isEmpty()) {
            loadDialog.ionizationChanged(PrecursorIonType.unknown(1));
            loadDialog.experimentNameChanged("");
        }
    }

    @Override
    public void completeProcess() {
        if (!spectra.isEmpty()) {
            if (expToModify == null) {
                expToModify = new ExperimentContainer(new MutableMs2Experiment());
            } else {
                expToModify.getMs2Experiment().getMs1Spectra().clear();
                expToModify.getMs2Experiment().getMs2Spectra().clear();
            }

            //add spectra
            for (SpectrumContainer container : spectra) {
                Spectrum<?> spectrum = container.getSpectrum(); // this return already the modified version if one exists
                if (spectrum.getMsLevel() == 1) {
                    if (container.isModified())
                        expToModify.getMs2Experiment().getMs1Spectra().add(new SimpleSpectrum(spectrum));
                    else
                        expToModify.getMs2Experiment().getMs1Spectra().add((SimpleSpectrum) spectrum);
                } else {
                    expToModify.getMs2Experiment().getMs2Spectra().add((MutableMs2Spectrum) spectrum);
                }
            }

            expToModify.setIonization(loadDialog.getIonization());
            expToModify.setIonMass(loadDialog.getParentMass());
            expToModify.setName(loadDialog.getExperimentName());
            expToModify.getMs2Experiment().setSource(source);
        }
    }

    @Override
    public void changeCollisionEnergy(SpectrumContainer container) {
        Spectrum sp = container.getSpectrum();
        double oldMin, oldMax;
        if (sp.getCollisionEnergy() == null) {
            oldMin = 0;
            oldMax = 0;
        } else {
            oldMin = sp.getCollisionEnergy().getMinEnergy();
            oldMax = sp.getCollisionEnergy().getMaxEnergy();
        }

        CollisionEnergyDialog ced = new CollisionEnergyDialog((JDialog) loadDialog, oldMin, oldMax);
        if (ced.getReturnValue() == ReturnValue.Success) {
            double newMin = ced.getMinCollisionEnergy();
            double newMax = ced.getMaxCollisionEnergy();
            if (oldMin != newMin || oldMax != newMax) {
                container.getModifiableSpectrum().setCollisionEnergy(new CollisionEnergy(newMin, newMax));
                loadDialog.newCollisionEnergy(container);
            }
        }
    }

    @Override
    public void changeMSLevel(final SpectrumContainer container, int msLevel) {
        //indentity chekc already done before listener call
        MutableMs2Spectrum mod = container.getModifiableSpectrum();
        mod.setMsLevel(msLevel);
        loadDialog.msLevelChanged(container);
    }

    @Override
    public void addSpectra(List<File> files) {
        importSpectra(files);
    }

    public void addSpectra(List<File> csvFiles, List<File> msFiles, List<File> mgfFiles) {
        importSpectra(csvFiles, msFiles, mgfFiles);
    }

}
