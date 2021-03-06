/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Olivier Lamy
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.maven;

import hudson.Launcher;
import hudson.maven.MavenBuild.ProxyImpl2;
import hudson.maven.util.ExecutionEventLogger;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.remoting.Channel;
import hudson.remoting.DelegatingCallable;
import hudson.util.IOException2;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.text.NumberFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.apache.maven.cli.PrintStreamLogger;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.jvnet.hudson.maven3.agent.Maven3Main;
import org.jvnet.hudson.maven3.launcher.Maven3Launcher;
import org.jvnet.hudson.maven3.listeners.HudsonMavenExecutionResult;

/**
 * @author Olivier Lamy
 *
 */
public class Maven3Builder extends AbstractMavenBuilder implements DelegatingCallable<Result,IOException> {

    /**
     * Flag needs to be set at the constructor, so that this reflects
     * the setting at master.
     */
    private final boolean profile = MavenProcessFactory.profile;
    
    HudsonMavenExecutionResult mavenExecutionResult;    
    
    private final Map<ModuleName,MavenBuildProxy2> proxies;
    private final Map<ModuleName,ProxyImpl2> sourceProxies;
    private final Map<ModuleName,List<MavenReporter>> reporters = new HashMap<ModuleName,List<MavenReporter>>();
    
    private final MavenBuildInformation mavenBuildInformation;
    
    protected Maven3Builder(BuildListener listener,Map<ModuleName,ProxyImpl2> proxies, Map<ModuleName,List<MavenReporter>> reporters, List<String> goals, Map<String, String> systemProps, MavenBuildInformation mavenBuildInformation) {
        super( listener, goals, systemProps );
        this.mavenBuildInformation = mavenBuildInformation;
        sourceProxies = new HashMap<ModuleName, ProxyImpl2>(proxies);
        this.proxies = new HashMap<ModuleName, MavenBuildProxy2>(proxies);
        for (Entry<ModuleName,MavenBuildProxy2> e : this.proxies.entrySet())
            e.setValue(new FilterImpl(e.getValue(), this.mavenBuildInformation));

        this.reporters.putAll( reporters );
    }    
    
    public Result call() throws IOException {

        MavenExecutionListener mavenExecutionListener = new MavenExecutionListener( this );
        try {
            initializeAsynchronousExecutions();
            
            Maven3Launcher.setMavenExecutionListener( mavenExecutionListener );
            
            markAsSuccess = false;

            registerSystemProperties();

            listener.getLogger().println(formatArgs(goals));
            
            
            int r = Maven3Main.launch( goals.toArray(new String[goals.size()]));

            // now check the completion status of async ops
            long startTime = System.nanoTime();
            
            Result waitForAsyncExecutionsResult = waitForAsynchronousExecutions();
            if (waitForAsyncExecutionsResult != null) {
                return waitForAsyncExecutionsResult;
            }
            
            mavenExecutionListener.overheadTime += System.nanoTime()-startTime;

            if(profile) {
                NumberFormat n = NumberFormat.getInstance();
                PrintStream logger = listener.getLogger();
                logger.println("Total overhead was "+format(n,mavenExecutionListener.overheadTime)+"ms");
                Channel ch = Channel.current();
                logger.println("Class loading "   +format(n,ch.classLoadingTime.get())   +"ms, "+ch.classLoadingCount+" classes");
                logger.println("Resource loading "+format(n,ch.resourceLoadingTime.get())+"ms, "+ch.resourceLoadingCount+" times");                
            }

            mavenExecutionResult = Maven3Launcher.getMavenExecutionResult();
            
            PrintStream logger = listener.getLogger();
            
            if(r==0 && mavenExecutionResult.getThrowables().isEmpty()) return Result.SUCCESS;
            
            if (!mavenExecutionResult.getThrowables().isEmpty()) {
                logger.println( "mavenExecutionResult exceptions not empty");
                for(Throwable throwable : mavenExecutionResult.getThrowables()) {
                    logger.println("message : " + throwable.getMessage());
                    if (throwable.getCause()!=null) {
                        logger.println("cause : " + throwable.getCause().getMessage());
                    }
                    logger.println("Stack trace : ");
                    throwable.printStackTrace( logger );
                }
                
            }

            if(markAsSuccess) {
                listener.getLogger().println(Messages.MavenBuilder_Failed());
                return Result.SUCCESS;
            }
            return Result.FAILURE;
        } catch (NoSuchMethodException e) {
            throw new IOException2(e);
        } catch (IllegalAccessException e) {
            throw new IOException2(e);
        } catch (InvocationTargetException e) {
            throw new IOException2(e);
        } catch (ClassNotFoundException e) {
            throw new IOException2(e);
        } catch (Exception e) {
            throw new IOException2(e);
        }
    }

    /**
     * Invoked after the maven has finished running, and in the master, not in the maven process.
     */
    void end(Launcher launcher) throws IOException, InterruptedException {
        for (Map.Entry<ModuleName,ProxyImpl2> e : sourceProxies.entrySet()) {
            ProxyImpl2 p = e.getValue();
            for (MavenReporter r : reporters.get(e.getKey())) {
                // we'd love to do this when the module build ends, but doing so requires
                // we know how many task segments are in the current build.
                r.end(p.owner(),launcher,listener);
                p.appendLastLog();
            }
            p.close();
        }
    }      

    private static final class MavenExecutionListener extends AbstractExecutionListener implements Serializable, ExecutionListener {

        private static final long serialVersionUID = 4942789836756366116L;

        private final Maven3Builder maven3Builder;
       
        /**
         * Number of total nanoseconds {@link Maven3Builder} spent.
         */
        long overheadTime;
        
       
        private final Map<ModuleName,MavenBuildProxy2> proxies;
        
        private final Map<ModuleName,List<ExecutedMojo>> executedMojosPerModule = new ConcurrentHashMap<ModuleName, List<ExecutedMojo>>();
        
        private final Map<ModuleName,List<MavenReporter>> reporters = new ConcurrentHashMap<ModuleName,List<MavenReporter>>();
        
        private final Map<ModuleName, Long> currentMojoStartPerModuleName = new ConcurrentHashMap<ModuleName, Long>();
        
        private ExecutionEventLogger eventLogger;

        public MavenExecutionListener(Maven3Builder maven3Builder) {
            this.maven3Builder = maven3Builder;
            this.proxies = new ConcurrentHashMap<ModuleName, MavenBuildProxy2>(maven3Builder.proxies);
            for (Entry<ModuleName,MavenBuildProxy2> e : this.proxies.entrySet())
            {
                e.setValue(maven3Builder.new FilterImpl(e.getValue(), maven3Builder.mavenBuildInformation, Channel.current()));
                executedMojosPerModule.put( e.getKey(), new CopyOnWriteArrayList<ExecutedMojo>() );
            }
            this.reporters.putAll( new ConcurrentHashMap<ModuleName, List<MavenReporter>>(maven3Builder.reporters) );
            this.eventLogger = new ExecutionEventLogger( new PrintStreamLogger( maven3Builder.listener.getLogger() ) );
        }
        
        private MavenBuildProxy2 getMavenBuildProxy2(MavenProject mavenProject) {
            for (Entry<ModuleName,MavenBuildProxy2> entry : proxies.entrySet()) {   
               if (entry.getKey().compareTo( new ModuleName( mavenProject ) ) == 0) {
                   return entry.getValue();
               }
            }
            return null;
        }
        
        private ExpressionEvaluator getExpressionEvaluator(MavenSession session, MojoExecution mojoExecution) {
            return new PluginParameterExpressionEvaluator( session, mojoExecution );
        }

        private List<MavenReporter> getMavenReporters(MavenProject mavenProject) {
            return reporters.get( new ModuleName( mavenProject ) );
        }        
        
        private void initMojoStartTime( MavenProject mavenProject) {
            this.currentMojoStartPerModuleName.put( new ModuleName(mavenProject), new Date().getTime() );
        }
        
        private Long getMojoStartTime(MavenProject mavenProject) {
            return currentMojoStartPerModuleName.get( new ModuleName(mavenProject) );
        }
        
        /**
         * @see org.apache.maven.execution.ExecutionListener#projectDiscoveryStarted(org.apache.maven.execution.ExecutionEvent)
         */
        public void projectDiscoveryStarted( ExecutionEvent event ) {
            this.eventLogger.projectDiscoveryStarted( event );
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#sessionStarted(org.apache.maven.execution.ExecutionEvent)
         */
        public void sessionStarted( ExecutionEvent event ) {
            this.eventLogger.sessionStarted( event );
            
            // set all modules which are not actually being build (in incremental builds) to NOT_BUILD
            // JENKINS-9072
            List<MavenProject> projects = event.getSession().getProjects();
            debug("Projects to build: " + projects);
            Set<ModuleName> buildingProjects = new HashSet<ModuleName>();
            for (MavenProject p : projects) {
                buildingProjects.add(new ModuleName(p));
            }
            
            for (Entry<ModuleName,MavenBuildProxy2> e : this.proxies.entrySet()) {
                if (! buildingProjects.contains(e.getKey())) {
                    //maven3Builder.listener.getLogger().println("Project " + e.getKey() + " needs not be build");
                    MavenBuildProxy2 proxy = e.getValue();
                    proxy.start();
                    proxy.setResult(Result.NOT_BUILT);
                    proxy.end();
                }
            }
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#sessionEnded(org.apache.maven.execution.ExecutionEvent)
         */
        public void sessionEnded( ExecutionEvent event )  {
            debug( "sessionEnded" );
            this.eventLogger.sessionEnded( event );
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#projectSkipped(org.apache.maven.execution.ExecutionEvent)
         */
        public void projectSkipped( ExecutionEvent event ) {
            debug("projectSkipped " + event.getProject().getGroupId()
                                                       + ":"  + event.getProject().getArtifactId()
                                                       + ":" + event.getProject().getVersion());    
            this.eventLogger.projectSkipped( event );
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#projectStarted(org.apache.maven.execution.ExecutionEvent)
         */
        public void projectStarted( ExecutionEvent event ) {
            debug( "projectStarted " + event.getProject().getGroupId() + ":"
                                                            + event.getProject().getArtifactId() + ":" + event.getProject().getVersion() );
            recordProjectStarted(event);
            this.eventLogger.projectStarted( event );
            
        }
        
        private void recordProjectStarted(ExecutionEvent event) {
            MavenProject mavenProject = event.getProject();
            List<MavenReporter> mavenReporters = getMavenReporters( mavenProject );                
            
            MavenBuildProxy2 mavenBuildProxy2 = getMavenBuildProxy2( mavenProject );
            mavenBuildProxy2.start();
            
            
            if (mavenReporters != null) {
                for (MavenReporter mavenReporter : mavenReporters) {
                    try {
                        mavenReporter.enterModule( mavenBuildProxy2 ,mavenProject, maven3Builder.listener);
                    } catch ( InterruptedException e ) {
                        e.printStackTrace();
                    } catch ( IOException e ) {
                        e.printStackTrace();
                    }
                }
            }            
            
            if (mavenReporters != null) {
                for (MavenReporter mavenReporter : mavenReporters) {
                    try {
                        mavenReporter.preBuild( mavenBuildProxy2 ,mavenProject, maven3Builder.listener);
                    } catch ( InterruptedException e ) {
                        e.printStackTrace();
                    } catch ( IOException e ) {
                        e.printStackTrace();
                    }
                }
            }             
            
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#projectSucceeded(org.apache.maven.execution.ExecutionEvent)
         */
        public void projectSucceeded( ExecutionEvent event ) {
            debug( "projectSucceeded "
                                                            + event.getProject().getGroupId() + ":"
                                                            + event.getProject().getArtifactId() + ":"
                                                            + event.getProject().getVersion());
            recordProjectSucceeded(event);
            this.eventLogger.projectSucceeded( event );
        }
        
        private void recordProjectSucceeded(ExecutionEvent event) {
            MavenBuildProxy2 mavenBuildProxy2 = getMavenBuildProxy2( event.getProject() );
            mavenBuildProxy2.setResult( Result.SUCCESS );
            
            
            List<MavenReporter> mavenReporters = getMavenReporters( event.getProject() );
            
            if ( mavenReporters != null ) {
                for ( MavenReporter mavenReporter : mavenReporters ) {
                    try {
                        mavenReporter.leaveModule( mavenBuildProxy2, event.getProject(), maven3Builder.listener);
                    } catch ( InterruptedException e ) {
                        e.printStackTrace();
                    } catch ( IOException e ) {
                        e.printStackTrace();
                    }
                }
            }             
            
            if ( mavenReporters != null ) {
                for ( MavenReporter mavenReporter : mavenReporters ) {
                    try {
                        mavenReporter.postBuild( mavenBuildProxy2, event.getProject(), maven3Builder.listener);
                    } catch ( InterruptedException e ) {
                        e.printStackTrace();
                    } catch ( IOException e ) {
                        e.printStackTrace();
                    }
                }
            }
           
            mavenBuildProxy2.end();
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#projectFailed(org.apache.maven.execution.ExecutionEvent)
         */
        public void projectFailed( ExecutionEvent event ) {
            debug("projectFailed " + event.getProject().getGroupId()
                                                                        + ":"  + event.getProject().getArtifactId()
                                                                        + ":" + event.getProject().getVersion());
            recordProjectFailed(event);
            this.eventLogger.projectFailed( event );
        }
        
        private void recordProjectFailed(ExecutionEvent event) {
            MavenBuildProxy2 mavenBuildProxy2 = getMavenBuildProxy2( event.getProject() );
            mavenBuildProxy2.setResult( Result.FAILURE );
            MavenProject mavenProject = event.getProject();
            List<MavenReporter> mavenReporters = getMavenReporters( mavenProject );
            
            if ( mavenReporters != null ) {
                for ( MavenReporter mavenReporter : mavenReporters ) {
                    try {
                        mavenReporter.leaveModule( mavenBuildProxy2, mavenProject, maven3Builder.listener);
                    } catch ( InterruptedException e ) {
                        e.printStackTrace();
                    } catch ( IOException e ) {
                        e.printStackTrace();
                    }
                }
            }             
            
            if ( mavenReporters != null ) {
                for ( MavenReporter mavenReporter : mavenReporters ) {
                    try {
                        mavenReporter.postBuild( mavenBuildProxy2, mavenProject, maven3Builder.listener);
                    } catch ( InterruptedException e ) {
                        e.printStackTrace();
                    } catch ( IOException e ) {
                        e.printStackTrace();
                    }
                }
            }

            mavenBuildProxy2.end();
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#mojoSkipped(org.apache.maven.execution.ExecutionEvent)
         */
        public void mojoSkipped( ExecutionEvent event ) {
            debug("mojoSkipped " + event.getMojoExecution().getGroupId() + ":"
                                                       + event.getMojoExecution().getArtifactId() + ":"
                                                       + event.getMojoExecution().getVersion()
                                                       + "(" + event.getMojoExecution().getExecutionId() + ")");
            this.eventLogger.mojoSkipped( event );
        }
        
        /**
         * @see org.apache.maven.execution.ExecutionListener#mojoStarted(org.apache.maven.execution.ExecutionEvent)
         */
        public void mojoStarted( ExecutionEvent event ) {
            debug("mojoStarted " + event.getMojoExecution().getGroupId() + ":"
                                                                      + event.getMojoExecution().getArtifactId() + ":"
                                                                      + event.getMojoExecution().getVersion()
                                                                      + "(" + event.getMojoExecution().getExecutionId() + ")");
            recordMojoStarted(event);
            this.eventLogger.mojoStarted( event );
        }
        
        private void recordMojoStarted(ExecutionEvent event) {
            initMojoStartTime( event.getProject() );
            
            MavenProject mavenProject = event.getProject();
            MojoInfo mojoInfo = new MojoInfo(event);

            List<MavenReporter> mavenReporters = getMavenReporters( mavenProject );                
            
            MavenBuildProxy2 mavenBuildProxy2 = getMavenBuildProxy2( mavenProject );
            
            if (mavenReporters != null) {
                for (MavenReporter mavenReporter : mavenReporters) {
                    try {
                        mavenReporter.preExecute( mavenBuildProxy2, mavenProject, mojoInfo, maven3Builder.listener);
                    } catch ( InterruptedException e ) {
                        e.printStackTrace();
                    } catch ( IOException e ) {
                        e.printStackTrace();
                    }
                }
            }            
        }        

        /**
         * @see org.apache.maven.execution.ExecutionListener#mojoSucceeded(org.apache.maven.execution.ExecutionEvent)
         */
        public void mojoSucceeded( ExecutionEvent event ) {
            debug("mojoSucceeded " + event.getMojoExecution().getGroupId() + ":"
                                                       + event.getMojoExecution().getArtifactId() + ":"
                                                       + event.getMojoExecution().getVersion()
                                                       + "(" + event.getMojoExecution().getExecutionId() + ")");
            recordMojoSucceeded(event);
            this.eventLogger.mojoSucceeded( event );
        }
        
        private void recordMojoSucceeded(ExecutionEvent event) {
            MavenProject mavenProject = event.getProject();
            MojoInfo mojoInfo = new MojoInfo(event);

            recordExecutionTime(event,mojoInfo);

            List<MavenReporter> mavenReporters = getMavenReporters( mavenProject );                
            
            MavenBuildProxy2 mavenBuildProxy2 = getMavenBuildProxy2( mavenProject );
            
            mavenBuildProxy2.setExecutedMojos( this.executedMojosPerModule.get( new ModuleName(event) ) );
            
            if (mavenReporters != null) {
                for (MavenReporter mavenReporter : mavenReporters) {
                    try {
                        mavenReporter.postExecute( mavenBuildProxy2, mavenProject, mojoInfo, maven3Builder.listener, getExecutionException(event));
                    } catch ( InterruptedException e ) {
                        e.printStackTrace();
                    } catch ( IOException e ) {
                        e.printStackTrace();
                    }
                }
            }
        }

        /**
         * Record how long it took to run this mojo.
         */
        private void recordExecutionTime(ExecutionEvent event, MojoInfo mojoInfo) {
            MavenProject p = event.getProject();
            List<ExecutedMojo> m = executedMojosPerModule.get(new ModuleName(p));
            if (m==null)    // defensive check
                executedMojosPerModule.put(new ModuleName(p), m=new CopyOnWriteArrayList<ExecutedMojo>());

            Long startTime = getMojoStartTime( event.getProject() );
            m.add(new ExecutedMojo( mojoInfo, startTime == null ? 0 : new Date().getTime() - startTime ));
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#mojoFailed(org.apache.maven.execution.ExecutionEvent)
         */
        public void mojoFailed( ExecutionEvent event ) {
            debug("mojoFailed " + event.getMojoExecution().getGroupId() + ":"
                                                       + event.getMojoExecution().getArtifactId() + ":"
                                                       + event.getMojoExecution().getVersion()
                                                       + "(" + event.getMojoExecution().getExecutionId() + ")");
            recordMojoFailed(event);
            this.eventLogger.mojoFailed( event );
        }

        private void debug(String msg) {
            LOGGER.fine(msg);
            if (DEBUG)
                maven3Builder.listener.getLogger().println(msg);
        }
        
        private void recordMojoFailed(ExecutionEvent event) {
            MavenProject mavenProject = event.getProject();
            MojoInfo mojoInfo = new MojoInfo(event);

            recordExecutionTime(event,mojoInfo);

            List<MavenReporter> mavenReporters = getMavenReporters( mavenProject );                
            
            MavenBuildProxy2 mavenBuildProxy2 = getMavenBuildProxy2( mavenProject );
            
            mavenBuildProxy2.setExecutedMojos( this.executedMojosPerModule.get( new ModuleName(event) ) );
            
            if (mavenReporters != null) {
                for (MavenReporter mavenReporter : mavenReporters) {
                    try {
                        mavenReporter.postExecute( mavenBuildProxy2, mavenProject, mojoInfo, maven3Builder.listener, getExecutionException(event) );
                    } catch ( InterruptedException e ) {
                        e.printStackTrace();
                    } catch ( IOException e ) {
                        e.printStackTrace();
                    }
                }
            }            
        }
        
        private Exception getExecutionException(ExecutionEvent event) {
            // http://issues.jenkins-ci.org/browse/JENKINS-8493
            // with maven 3.0.2 see http://jira.codehaus.org/browse/MNG-4922
            // catch NoSuchMethodError if folks not using 3.0.2+
            try {
                return event.getException();
            } catch (NoSuchMethodError e) {
                return null;
            }
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#forkStarted(org.apache.maven.execution.ExecutionEvent)
         */
        public void forkStarted( ExecutionEvent event )
        {
            LOGGER.fine("mojo forkStarted " + event.getMojoExecution().getGroupId() + ":"
                                                       + event.getMojoExecution().getArtifactId() + ":"
                                                       + event.getMojoExecution().getVersion()
                                                       + "(" + event.getMojoExecution().getExecutionId() + ")");
            recordMojoStarted(event);
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#forkSucceeded(org.apache.maven.execution.ExecutionEvent)
         */
        public void forkSucceeded( ExecutionEvent event ) {
            LOGGER.fine("mojo forkSucceeded " + event.getMojoExecution().getGroupId() + ":"
                                                       + event.getMojoExecution().getArtifactId() + ":"
                                                       + event.getMojoExecution().getVersion()
                                                       + "(" + event.getMojoExecution().getExecutionId() + ")");
            recordMojoSucceeded(event);
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#forkFailed(org.apache.maven.execution.ExecutionEvent)
         */
        public void forkFailed( ExecutionEvent event ) {
            LOGGER.fine("mojo forkFailed " + event.getMojoExecution().getGroupId() + ":"
                                                       + event.getMojoExecution().getArtifactId() + ":"
                                                       + event.getMojoExecution().getVersion()
                                                       + "(" + event.getMojoExecution().getExecutionId() + ")");  
            recordMojoFailed(event);
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#forkedProjectStarted(org.apache.maven.execution.ExecutionEvent)
         */
        public void forkedProjectStarted( ExecutionEvent event ) {
            LOGGER.fine( "forkedProjectStarted " + event.getProject().getGroupId() + ":"
                                                        + event.getProject().getArtifactId() + event.getProject().getVersion() );
            recordProjectStarted(event);
            this.eventLogger.forkedProjectStarted( event );
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#forkedProjectSucceeded(org.apache.maven.execution.ExecutionEvent)
         */
        public void forkedProjectSucceeded( ExecutionEvent event ) {
            LOGGER.fine( "forkedProjectSucceeded "
                                                        + event.getProject().getGroupId() + ":"
                                                        + event.getProject().getArtifactId()
                                                        + event.getProject().getVersion());
            recordProjectSucceeded(event);
            this.eventLogger.forkedProjectSucceeded( event );
        }

        /**
         * @see org.apache.maven.execution.ExecutionListener#forkedProjectFailed(org.apache.maven.execution.ExecutionEvent)
         */
        public void forkedProjectFailed( ExecutionEvent event ) {
            LOGGER.fine("forkedProjectFailed " + event.getProject().getGroupId()
                                                       + ":"  + event.getProject().getArtifactId()
                                                       + ":" + event.getProject().getVersion());
            recordProjectFailed(event);
        }
    }

    public static boolean markAsSuccess;

    private static final long serialVersionUID = 1L;

    public static boolean DEBUG = false;

    private static final Logger LOGGER = Logger.getLogger(Maven3Builder.class.getName());
}
