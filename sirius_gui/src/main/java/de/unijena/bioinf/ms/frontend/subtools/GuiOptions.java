package de.unijena.bioinf.ms.frontend.subtools;

import de.unijena.bioinf.fingerid.webapi.VersionsInfo;
import de.unijena.bioinf.ms.frontend.core.ApplicationCore;
import de.unijena.bioinf.ms.frontend.core.SiriusProperties;
import de.unijena.bioinf.ms.frontend.io.projectspace.ProjectSpaceManager;
import de.unijena.bioinf.ms.frontend.workflow.Workflow;
import de.unijena.bioinf.ms.gui.compute.jjobs.Jobs;
import de.unijena.bioinf.ms.gui.dialogs.NewsDialog;
import de.unijena.bioinf.ms.gui.dialogs.UpdateDialog;
import de.unijena.bioinf.ms.gui.mainframe.MainFrame;
import de.unijena.bioinf.ms.gui.net.ConnectionMonitor;
import de.unijena.bioinf.ms.gui.utils.GuiUtils;
import de.unijena.bioinf.ms.properties.ParameterConfig;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

@CommandLine.Command(name = "gui", aliases = {"GUI"}, description = "Starts the graphical user interface of SIRIUS", defaultValueProvider = Provide.Defaults.class, versionProvider = Provide.Versions.class, mixinStandardHelpOptions = true)
public class GuiOptions implements SingletonTool {
    @Override
    public Workflow makeSingletonWorkflow(PreprocessingJob preproJob, ProjectSpaceManager projectSpace, ParameterConfig config) {
        return () -> {
            //todo minor: cancellation handling

            //todo 1:  run prepro and

            //todo 2:  run addConfig job

            //todo 3: init GUI with given project space.
            GuiUtils.initUI();
            ApplicationCore.DEFAULT_LOGGER.info("Swing parameters for GUI initialized");
            MainFrame.MF.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    try {
                        ApplicationCore.DEFAULT_LOGGER.info("Saving properties file before termination.");
                        SiriusProperties.SIRIUS_PROPERTIES_FILE().store();
                        ApplicationCore.DEFAULT_LOGGER.info("Writing Summaries to Project-Space before termination.");
                        projectSpace.updateSummaries(ProjectSpaceManager.defaultSummarizer());
                        projectSpace.close();
                    } catch (IOException e) {
                        ApplicationCore.DEFAULT_LOGGER.error("Could not write summaries", e);
                    } finally {
                        System.exit(0);
                    }
                }
            });
            MainFrame.MF.setLocationRelativeTo(null);//init mainframe
            ApplicationCore.DEFAULT_LOGGER.info("GUI initialized, showing GUI..");
            MainFrame.MF.decoradeMainFrameInstance();

            ApplicationCore.DEFAULT_LOGGER.info("Checking client version and webservice connection...");
            Jobs.runInBackround(() -> {
                ConnectionMonitor.ConnetionCheck cc = MainFrame.CONNECTION_MONITOR.checkConnection();
                if (cc.isConnected()) {
                    @Nullable VersionsInfo versionsNumber = ApplicationCore.WEB_API.getVersionInfo();
                    ApplicationCore.DEFAULT_LOGGER.debug("FingerID response " + (versionsNumber != null ? String.valueOf(versionsNumber.toString()) : "NULL"));
                    if (versionsNumber != null) {
                        if (versionsNumber.expired()) {
                            new UpdateDialog(MainFrame.MF, versionsNumber);
                        }
                        if (!versionsNumber.outdated()) {
                            MainFrame.MF.setFingerIDEnabled(true);
                        }
                        if (versionsNumber.hasNews()) {
                            new NewsDialog(MainFrame.MF, versionsNumber.getNews());
                        }
                    }
                }
            });
        };
    }
}
