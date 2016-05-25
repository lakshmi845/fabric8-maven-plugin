/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.enricher.icon;

import java.io.*;
import java.util.Collections;
import java.util.Map;

import io.fabric8.maven.core.util.MavenUtil;
import io.fabric8.maven.enricher.api.*;
import io.fabric8.utils.*;
import org.apache.maven.model.Scm;
import org.apache.maven.project.MavenProject;

import static io.fabric8.utils.Files.guessMediaType;

/**
 * Enrichre for adding icons to descriptors
 *
 * @author roland
 * @since 01/05/16
 */
public class IconEnricher extends BaseEnricher {

    private static String[] ICON_EXTENSIONS = new String[]{".svg", ".png", ".gif", ".jpg", ".jpeg"};

    private File templateTempDir;
    private File appConfigDir;
    private String iconRef;
    private int maximumDataUrlSizeK;
    private String iconUrlPrefix;
    private String iconBranch;
    private String iconUrl;

    public IconEnricher(EnricherContext buildContext) {
        super(buildContext);

        EnricherConfiguration config = getConfig();

        String dir = config.get("templateTempDir");
        templateTempDir =
            dir != null ? new File(dir) : new File(getProject().getBasedir() + "/target/fabric8/template-workdir");
        dir = config.get("source.dir");
        appConfigDir =
            dir != null ? new File(dir) : new File(getProject().getBasedir() + "/src/main/fabric8");
        iconRef = config.get("iconRef");
        maximumDataUrlSizeK = config.getAsInt("maximumDataUrlSizeK",2);
        iconUrlPrefix = config.get("iconUrlPrefix");
        iconBranch = config.get("iconBranch", "master");
        iconUrl = config.get("iconUrl");
    }

    @Override
    public String getName() {
        return "icon";
    }

    @Override
    public Map<String, String> getAnnotations(Kind kind) {
        if (kind == Kind.REPLICA_SET || kind == Kind.REPLICATION_CONTROLLER || kind == Kind.SERVICE) {
            String iconUrl = getIconUrl();
            return iconUrl != null ? Collections.singletonMap("fabric8.io/iconUrl",iconUrl) : null;
        } else {
            return null;
        }
    }

    // ====================================================================================================

    protected String getIconUrl() {
        String answer = iconUrl;
        if (Strings.isNullOrBlank(answer)) {
            try {
                if (templateTempDir != null) {
                    templateTempDir.mkdirs();
                    File iconFile = copyIconToFolder(iconRef, templateTempDir);
                    if (iconFile == null) {
                        copyAppConfigFiles(templateTempDir, appConfigDir);

                        // lets find the icon file...
                        for (String ext : ICON_EXTENSIONS) {
                            File file = new File(templateTempDir, "icon" + ext);
                            if (file.exists() && file.isFile()) {
                                iconFile = file;
                                break;
                            }
                        }
                    }
                    if (iconFile != null) {
                        answer = convertIconFileToURL(iconFile);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load icon file: " + e, e);
            }
        }

        if (Strings.isNullOrBlank(answer)) {
            // maybe its a common icon that is embedded in fabric8-console
            String embeddedIcon = embeddedIconsInConsole(iconRef, "img/icons/");
            if (embeddedIcon != null) {
                return embeddedIcon;
            }
        }

        if (Strings.isNullOrBlank(answer)) {
            log.debug("No icon file found for this project");
        } else {
            log.info("Icon URL: " + answer);
        }

        return answer;
    }

    private File copyIconToFolder(String iconRef, File appBuildDir) throws IOException {
        if (Strings.isNotBlank(iconRef)) {
            File[] icons = appBuildDir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    if (name == null) {
                        return false;
                    }
                    String lower = name.toLowerCase();
                    if (lower.startsWith("icon.")) {
                        for (String ext : ICON_EXTENSIONS) {
                            if (lower.endsWith(ext)) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
            });
            if (icons == null || icons.length == 0) {
                // lets copy the iconRef
                InputStream in = loadPluginResource(iconRef);
                if (in == null) {
                    // maybe it dont have extension so try to find it
                    for (String ext : ICON_EXTENSIONS) {
                        String name = iconRef + ext;
                        in = loadPluginResource(name);
                        if (in != null) {
                            iconRef = name;
                            break;
                        }
                    }
                }
                if (in != null) {
                    String fileName = "icon." + Files.getFileExtension(iconRef);
                    File outFile = new File(appBuildDir, fileName);
                    Files.copy(in, new FileOutputStream(outFile));
                    log.info("Generated icon file " + outFile + " from icon reference: " + iconRef);
                    return outFile;
                }
            }
        }
        return null;
    }

    private InputStream loadPluginResource(String iconRef) {
        InputStream answer = Thread.currentThread().getContextClassLoader().getResourceAsStream(iconRef);
        if (answer == null) {
            answer = MavenUtil.getTestClassLoader(getProject()).getResourceAsStream(iconRef);
        }
        if (answer == null) {
            answer = this.getClass().getResourceAsStream(iconRef);
        }
        return answer;
    }

    /**
     * Copies any local configuration files into the app directory
     */
    private void copyAppConfigFiles(File appBuildDir, File appConfigDir) throws IOException {
        File[] files = appConfigDir.listFiles();
        if (files != null) {
            appBuildDir.mkdirs();
            for (File file : files) {
                File outFile = new File(appBuildDir, file.getName());
                if (file.isDirectory()) {
                    copyAppConfigFiles(outFile, file);
                } else {
                    Files.copy(file, outFile);
                }
            }
        }
    }


    private String convertIconFileToURL(File iconFile) throws IOException {
        long length = iconFile.length();

        int sizeK = Math.round(length / 1024);

        byte[] bytes = Files.readBytes(iconFile);
        byte[] encoded = Base64Encoder.encode(bytes);

        int base64SizeK = Math.round(encoded.length / 1024);

        if (base64SizeK < maximumDataUrlSizeK) {
            String mimeType = guessMediaType(iconFile);
            return "data:" + mimeType + ";charset=UTF-8;base64," + new String(encoded);
        } else {
            File iconSourceFile = new File(appConfigDir, iconFile.getName());
            if (iconSourceFile.exists()) {
                File rootProjectFolder = getRootProjectFolder();
                if (rootProjectFolder != null) {
                    String relativePath = Files.getRelativePath(rootProjectFolder, iconSourceFile);
                    String relativeParentPath = Files.getRelativePath(rootProjectFolder, getProject().getBasedir());
                    String urlPrefix = iconUrlPrefix;
                    if (Strings.isNullOrBlank(urlPrefix)) {
                        Scm scm = getProject().getScm();
                        if (scm != null) {
                            String url = scm.getUrl();
                            if (url != null) {
                                String[] prefixes = {"http://github.com/", "https://github.com/"};
                                for (String prefix : prefixes) {
                                    if (url.startsWith(prefix)) {
                                        url = URLUtils.pathJoin("https://cdn.rawgit.com/", url.substring(prefix.length()));
                                        break;
                                    }
                                }
                                if (url.endsWith(relativeParentPath)) {
                                    url = url.substring(0, url.length() - relativeParentPath.length());
                                }
                                urlPrefix = url;
                            }
                        }
                    }
                    if (Strings.isNullOrBlank(urlPrefix)) {
                        log.warn("No iconUrlPrefix defined or could be found via SCM in the pom.xml so cannot add an icon URL!");
                    } else {
                        String answer = URLUtils.pathJoin(urlPrefix, iconBranch, relativePath);
                        return answer;
                    }
                }
            } else {
                String embeddedIcon = embeddedIconsInConsole(iconRef, "img/icons/");
                if (embeddedIcon != null) {
                    return embeddedIcon;
                } else {
                    log.warn("Cannot find url for icon to use " + iconUrl);
                }
            }
        }
        return null;
    }

    /**
     * Returns the root project folder
     */
    protected File getRootProjectFolder() {
        File answer = null;
        MavenProject project = getProject();
        while (project != null) {
            File basedir = project.getBasedir();
            if (basedir != null) {
                answer = basedir;
            }
            project = project.getParent();
        }
        return answer;
    }


    /**
     * To use embedded icons provided by the fabric8-console
     *
     * @param iconRef  name of icon file
     * @param prefix   prefix location for the icons in the fabric8-console
     * @return the embedded icon ref, or <tt>null</tt> if no embedded icon found to be used
     */
    protected String embeddedIconsInConsole(String iconRef, String prefix) {
        if (iconRef == null) {
            return null;
        }

        if (iconRef.startsWith("icons/")) {
            iconRef = iconRef.substring(6);
        }

        // special for fabric8 as its in a different dir
        if (iconRef.contains("fabric8")) {
            return "img/fabric8_icon.svg";
        }

        if (iconRef.contains("activemq")) {
            return prefix + "activemq.svg";
        } else if (iconRef.contains("apiman")) {
            return prefix + "apiman.png";
        } else if (iconRef.contains("api-registry")) {
            return prefix + "api-registry.svg";
        } else if (iconRef.contains("brackets")) {
            return prefix + "brackets.svg";
        } else if (iconRef.contains("camel")) {
            return prefix + "camel.svg";
        } else if (iconRef.contains("chaos-monkey")) {
            return prefix + "chaos-monkey.png";
        } else if (iconRef.contains("docker-registry")) {
            return prefix + "docker-registry.png";
        } else if (iconRef.contains("elasticsearch")) {
            return prefix + "elasticsearch.png";
        } else if (iconRef.contains("fluentd")) {
            return prefix + "fluentd.png";
        } else if (iconRef.contains("forge")) {
            return prefix + "forge.svg";
        } else if (iconRef.contains("gerrit")) {
            return prefix + "gerrit.png";
        } else if (iconRef.contains("gitlab")) {
            return prefix + "gitlab.svg";
        } else if (iconRef.contains("gogs")) {
            return prefix + "gogs.png";
        } else if (iconRef.contains("grafana")) {
            return prefix + "grafana.png";
        } else if (iconRef.contains("hubot-irc")) {
            return prefix + "hubot-irc.png";
        } else if (iconRef.contains("hubot-letschat")) {
            return prefix + "hubot-letschat.png";
        } else if (iconRef.contains("hubot-notifier")) {
            return prefix + "hubot-notifier.png";
        } else if (iconRef.contains("hubot-slack")) {
            return prefix + "hubot-slack.png";
        } else if (iconRef.contains("image-linker")) {
            return prefix + "image-linker.svg";
        } else if (iconRef.contains("javascript")) {
            return prefix + "javascript.png";
        } else if (iconRef.contains("java")) {
            return prefix + "java.svg";
        } else if (iconRef.contains("jenkins")) {
            return prefix + "jenkins.svg";
        } else if (iconRef.contains("jetty")) {
            return prefix + "jetty.svg";
        } else if (iconRef.contains("karaf")) {
            return prefix + "karaf.svg";
        } else if (iconRef.contains("keycloak")) {
            return prefix + "keycloak.svg";
        } else if (iconRef.contains("kibana")) {
            return prefix + "kibana.svg";
        } else if (iconRef.contains("kiwiirc")) {
            return prefix + "kiwiirc.png";
        } else if (iconRef.contains("letschat")) {
            return prefix + "letschat.png";
        } else if (iconRef.contains("mule")) {
            return prefix + "mule.svg";
        } else if (iconRef.contains("nexus")) {
            return prefix + "nexus.png";
        } else if (iconRef.contains("node")) {
            return prefix + "node.svg";
        } else if (iconRef.contains("orion")) {
            return prefix + "orion.png";
        } else if (iconRef.contains("prometheus")) {
            return prefix + "prometheus.png";
        } else if (iconRef.contains("django") || iconRef.contains("python")) {
            return prefix + "python.png";
        } else if (iconRef.contains("spring-boot")) {
            return prefix + "spring-boot.svg";
        } else if (iconRef.contains("taiga")) {
            return prefix + "taiga.png";
        } else if (iconRef.contains("tomcat")) {
            return prefix + "tomcat.svg";
        } else if (iconRef.contains("tomee")) {
            return prefix + "tomee.svg";
        } else if (iconRef.contains("vertx")) {
            return prefix + "vertx.svg";
        } else if (iconRef.contains("wildfly")) {
            return prefix + "wildfly.svg";
        } else if (iconRef.contains("weld")) {
            return prefix + "weld.svg";
        } else if (iconRef.contains("zipkin")) {
            return prefix + "zipkin.png";
        }

        return null;
    }

}
