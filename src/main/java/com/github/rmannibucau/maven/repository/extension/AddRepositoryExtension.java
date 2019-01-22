/**
 * Copyright (C) 2006-2019 Talend Inc. - www.talend.com
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rmannibucau.maven.repository.extension;

import static java.util.Comparator.comparing;
import static java.util.Optional.ofNullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.text.StringSubstitutor;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "rmannibucau-filter-repository")
public class AddRepositoryExtension extends AbstractMavenLifecycleParticipant {
    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        final MavenProject currentProject = session.getCurrentProject();
        if (currentProject == null) {
            return;
        }

        final Map<String, String> props = new HashMap<>();
        currentProject.getProperties().stringPropertyNames()
                      .forEach(k -> props.put(k, currentProject.getProperties().getProperty(k)));

        File dotGitDirectory = new File(currentProject.getBasedir(), ".git");
        if (!dotGitDirectory.exists()) {
            dotGitDirectory = session.getAllProjects().stream()
                   .map(it -> new File(it.getBasedir(), ".git"))
                   .filter(File::exists)
                   .sorted(comparing(File::length))
                   .findFirst()
                   .orElse(null);
        }
        if (dotGitDirectory != null && dotGitDirectory.exists()) {
            try(final Repository repository = new FileRepositoryBuilder().setGitDir(dotGitDirectory).readEnvironment().findGitDir().build()) {
                ofNullable(repository.getBranch()).ifPresent(value -> props.put("git.branch", value));
                ofNullable(repository.getFullBranch()).ifPresent(value -> props.put("git.fullBranch", value));

                final Ref head = repository.findRef("HEAD");
                ofNullable(head).ifPresent(value -> props.put("git.head", value.getName()));

                final RevWalk revWalk = new RevWalk(repository);
                final ObjectId headObjectId = head != null ? head.getObjectId() : repository.resolve("HEAD");
                final RevCommit commit = revWalk.parseCommit(headObjectId);
                ofNullable(commit).ifPresent(value -> props.put("git.commitId", value.getName()));
            } catch (final IOException e) {
                throw new MavenExecutionException("Could not initialize git repo", e);
            }
        }

        final StringSubstitutor stringSubstitutor = new StringSubstitutor(props);
        filterRepositories(currentProject.getRepositories(), stringSubstitutor);
        filterRepositories(currentProject.getPluginRepositories(), stringSubstitutor);
        filterArtifactRepositories(currentProject.getPluginArtifactRepositories(), stringSubstitutor);
        filterArtifactRepositories(currentProject.getRemoteArtifactRepositories(), stringSubstitutor);
        filterRemoteRepositories(currentProject.getRemoteProjectRepositories(), stringSubstitutor);
        filterRemoteRepositories(currentProject.getRemotePluginRepositories(), stringSubstitutor);
    }

    private void filterArtifactRepositories(final List<ArtifactRepository> repos, final StringSubstitutor stringSubstitutor) {
        repos.stream().filter(it -> it.getUrl().contains("${")).forEach(it -> it.setUrl(stringSubstitutor.replace(it.getUrl())));
    }

    private void filterRepositories(final List<org.apache.maven.model.Repository> repos, final StringSubstitutor stringSubstitutor) {
        repos.stream().filter(it -> it.getUrl().contains("${")).forEach(it -> it.setUrl(stringSubstitutor.replace(it.getUrl())));
    }

    private void filterRemoteRepositories(final List<RemoteRepository> repos, final StringSubstitutor stringSubstitutor) {
        final Iterator<RemoteRepository> iterator = repos.iterator();
        final Collection<RemoteRepository> added = new ArrayList<>();
        while (iterator.hasNext()) {
            final RemoteRepository repository = iterator.next();
            if (repository.getUrl().contains("${")) {
                final String newUrl = stringSubstitutor.replace(repository.getUrl());
                if (!newUrl.equalsIgnoreCase(repository.getUrl())) {
                    iterator.remove();
                    added.add(new RemoteRepository.Builder(repository.getId(), repository.getContentType(), newUrl)
                            .setAuthentication(repository.getAuthentication())
                            .setProxy(repository.getProxy())
                            .setMirroredRepositories(repository.getMirroredRepositories())
                            .setReleasePolicy(repository.getPolicy(false))
                            .setSnapshotPolicy(repository.getPolicy(true))
                            .setRepositoryManager(repository.isRepositoryManager())
                            .build());
                }
            }
        }
        repos.addAll(0, added);
    }
}

