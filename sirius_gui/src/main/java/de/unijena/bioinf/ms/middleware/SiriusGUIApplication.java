package de.unijena.bioinf.ms.middleware;

import de.unijena.bioinf.ChemistryBase.jobs.SiriusJobs;
import de.unijena.bioinf.jjobs.SwingJobManager;
import de.unijena.bioinf.ms.frontend.core.ApplicationCore;
import de.unijena.bioinf.ms.frontend.subtools.CLIRootOptions;
import de.unijena.bioinf.ms.frontend.subtools.config.DefaultParameterConfigLoader;
import de.unijena.bioinf.ms.frontend.workflow.ServiceWorkflow;
import de.unijena.bioinf.ms.frontend.workfow.GuiInstanceBufferFactory;
import de.unijena.bioinf.ms.frontend.workfow.GuiWorkflowBuilder;
import de.unijena.bioinf.ms.gui.compute.jjobs.Jobs;
import de.unijena.bioinf.ms.gui.configs.Fonts;
import de.unijena.bioinf.ms.properties.PropertyManager;
import de.unijena.bioinf.projectspace.GuiProjectSpaceManagerFactory;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.inchi.InChIGeneratorFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author Markus Fleischauer (markus.fleischauer@gmail.com)
 */

@SpringBootApplication
public class SiriusGUIApplication extends SiriusMiddlewareApplication {

    public SiriusGUIApplication(SiriusContext context) {
        super(context);
    }

    public static void main(String[] args) {
        //cancel all instances to quit
        configureShutDownHook(Jobs::cancelALL);
        ApplicationCore.DEFAULT_LOGGER.info("Application Core started");
        final int cpuThreads = Integer.valueOf(PropertyManager.getProperty("de.unijena.bioinf.sirius.cpu.cores", null, "1"));
        SiriusJobs.setGlobalJobManager(new SwingJobManager(PropertyManager.getNumberOfThreads(), Math.min(cpuThreads, 3)));
        ApplicationCore.DEFAULT_LOGGER.info("Swing Job MANAGER initialized! " + SiriusJobs.getGlobalJobManager().getCPUThreads() + " : " + SiriusJobs.getGlobalJobManager().getIOThreads());

        //improve rendering?
        Fonts.initFonts();

        try {
            InChIGeneratorFactory.getInstance();
            ApplicationCore.DEFAULT_LOGGER.info("CDK InChIGeneratorFactory configured.");
        } catch (CDKException e) {
            ApplicationCore.DEFAULT_LOGGER.error("Error configuring CDK InChIGeneratorFactory.",e);
        }

        run(args, () -> {
            final DefaultParameterConfigLoader configOptionLoader = new DefaultParameterConfigLoader();
            rootOptions = new CLIRootOptions<>(configOptionLoader, new GuiProjectSpaceManagerFactory());
            return new GuiWorkflowBuilder<>(rootOptions, configOptionLoader, new GuiInstanceBufferFactory());
        });

        if (!(RUN.getFlow() instanceof ServiceWorkflow)) {
            System.exit(0);
        }
    }
}
