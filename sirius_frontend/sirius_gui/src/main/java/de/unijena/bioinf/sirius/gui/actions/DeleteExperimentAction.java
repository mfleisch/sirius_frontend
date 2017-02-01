package de.unijena.bioinf.sirius.gui.actions;
/**
 * Created by Markus Fleischauer (markus.fleischauer@gmail.com)
 * as part of the sirius_frontend
 * 29.01.17.
 */

import ca.odell.glazedlists.event.ListEvent;
import de.unijena.bioinf.sirius.gui.dialogs.CloseDialogNoSaveReturnValue;
import de.unijena.bioinf.sirius.gui.dialogs.CloseDialogReturnValue;
import de.unijena.bioinf.sirius.gui.mainframe.ExperimentListChangeListener;
import de.unijena.bioinf.sirius.gui.mainframe.Workspace;
import de.unijena.bioinf.sirius.gui.structure.ExperimentContainer;
import de.unijena.bioinf.sirius.gui.utils.Icons;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.List;

import static de.unijena.bioinf.sirius.gui.mainframe.MainFrame.MF;
import static de.unijena.bioinf.sirius.gui.mainframe.Workspace.CONFIG_STORAGE;

/**
 * @author Markus Fleischauer (markus.fleischauer@gmail.com)
 */
public class DeleteExperimentAction extends AbstractAction {
    public DeleteExperimentAction() {
        super("Remove Experiment(s)");
        putValue(Action.SMALL_ICON, Icons.REMOVE_DOC_16);
        putValue(Action.SHORT_DESCRIPTION, "Remove selected Experiment(s)");

        setEnabled(!MF.getCompoundView().isSelectionEmpty());

        MF.getCompountListPanel().addChangeListener(new ExperimentListChangeListener() {
            @Override
            public void listChanged(ListEvent<ExperimentContainer> event, JList<ExperimentContainer> source) {}

            @Override
            public void listSelectionChanged(JList<ExperimentContainer> source) {
                setEnabled(!source.isSelectionEmpty());
            }
        });
    }
    @Override
    public void actionPerformed(ActionEvent e) {
        if (!CONFIG_STORAGE.isCloseNeverAskAgain()) {
            CloseDialogNoSaveReturnValue diag = new CloseDialogNoSaveReturnValue(MF, "When removing the selected experiment(s) you will loose all computed identification results?");
            CloseDialogReturnValue val = diag.getReturnValue();
            if (val == CloseDialogReturnValue.abort) return;
        }
        List<ExperimentContainer> toRemove = MF.getCompountListPanel().getCompoundListView().getSelectedValuesList();
        MF.getCompoundView().clearSelection();
        for (ExperimentContainer cont : toRemove) {
            MF.getBackgroundComputation().cancel(cont);
        }
        Workspace.removeAll(toRemove);
    }
}