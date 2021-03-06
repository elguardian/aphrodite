/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.set.aphrodite.issue.trackers.jira;

import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.API_ISSUE_PATH;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.BROWSE_ISSUE_PATH;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.FLAG_MAP;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.TARGET_RELEASE;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.getJiraTransition;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.set.aphrodite.common.Utils;
import org.jboss.set.aphrodite.config.IssueTrackerConfig;
import org.jboss.set.aphrodite.config.TrackerType;
import org.jboss.set.aphrodite.domain.Comment;
import org.jboss.set.aphrodite.domain.Flag;
import org.jboss.set.aphrodite.domain.Issue;
import org.jboss.set.aphrodite.domain.SearchCriteria;
import org.jboss.set.aphrodite.issue.trackers.common.AbstractIssueTracker;
import org.jboss.set.aphrodite.spi.AphroditeException;
import org.jboss.set.aphrodite.spi.NotFoundException;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClientFactory;
import com.atlassian.jira.rest.client.api.SearchRestClient;
import com.atlassian.jira.rest.client.api.domain.Filter;
import com.atlassian.jira.rest.client.api.domain.IssueLink;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.api.domain.Transition;
import com.atlassian.jira.rest.client.api.domain.IssueLinkType.Direction;
import com.atlassian.jira.rest.client.api.domain.input.IssueInput;
import com.atlassian.jira.rest.client.api.domain.input.LinkIssuesInput;
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;


/**
 * An implementation of the <code>IssueTrackerService</code> for the JIRA issue tracker.
 *
 * @author Ryan Emerson
 */
public class JiraIssueTracker extends AbstractIssueTracker {

    private static final Log LOG = LogFactory.getLog(JiraIssueTracker.class);

    private final IssueWrapper WRAPPER = new IssueWrapper();
    private final JiraQueryBuilder queryBuilder = new JiraQueryBuilder();
    private JiraRestClient restClient ;

    public JiraIssueTracker() {
        super(TrackerType.JIRA);
    }

    @Override
    public boolean init(IssueTrackerConfig config) {
        boolean parentInitiated = super.init(config);
        if (!parentInitiated)
            return false;

        try {
            JiraRestClientFactory factory = new AsynchronousJiraRestClientFactory();
            URI jiraServerUri = baseUrl.toURI();
            restClient = factory.createWithBasicHttpAuthentication(jiraServerUri, config.getUsername(), config.getPassword());
        } catch (Exception e) {
            Utils.logException(LOG, e);
            return false;
        }
        return true;
    }

    @Override
    public Issue getIssue(URL url) throws NotFoundException {
        try {
            checkHost(url);
            com.atlassian.jira.rest.client.api.domain.Issue issue;
            issue = restClient.getIssueClient().getIssue(getIssueKey(url)).get();
            return WRAPPER.jiraIssueToIssue(url, issue);
        } catch (InterruptedException e) {
            throw new NotFoundException("something interrupted the execution ", e);
        } catch (ExecutionException e) {
            throw new NotFoundException("problem during execution ", e);
        }

    }

    private com.atlassian.jira.rest.client.api.domain.Issue getIssue(Issue issue) throws NotFoundException {
        String trackerId = issue.getTrackerId().orElse(getIssueKey(issue.getURL()));
        return getIssue(trackerId);
    }

    private com.atlassian.jira.rest.client.api.domain.Issue getIssue(String trackerId) throws NotFoundException {
        try {
            return restClient.getIssueClient().getIssue(trackerId).get();
        } catch (Exception e) {
            throw new NotFoundException(e);
        }
    }

    @Override
    public List<Issue> getIssues(Collection<URL> urls) {
        urls = filterUrlsByHost(urls);
        if (urls.isEmpty())
            return new ArrayList<>();

        List<String> ids = new ArrayList<>();
        for (URL url : urls) {
            try {
                ids.add(getIssueKey(url));
            } catch (NotFoundException e) {
                if (LOG.isWarnEnabled())
                    LOG.warn("Unable to extract trackerId from: " + url);
            }
        }
        String jql = queryBuilder.getMultipleIssueJQL(ids);
        return searchIssues(jql, ids.size());
    }

    @Override
    public List<Issue> searchIssues(SearchCriteria searchCriteria) {
        String jql = queryBuilder.getSearchJQL(searchCriteria);
        int maxResults = searchCriteria.getMaxResults().orElse(config.getDefaultIssueLimit());
        return searchIssues(jql, maxResults);
    }

    private List<Issue> searchIssues(String jql, int maxResults) {
        try {
            List<Issue> issues = new ArrayList<>();
            SearchRestClient searchClient = restClient.getSearchClient();
            SearchResult result = searchClient.searchJql(jql, maxResults, null, null).get();
            result.getIssues().forEach(issue -> issues.add(WRAPPER.jiraSearchIssueToIssue(baseUrl, issue)));
            return issues;
        } catch (Exception e) {
            LOG.error("Problem executing jql " + jql, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<Issue> searchIssuesByFilter(URL filterUrl) throws NotFoundException {
        String jql = getJQLFromFilter(filterUrl);
        return searchIssues(jql, config.getDefaultIssueLimit());
    }

    private String getJQLFromFilter(URL filterUrl) throws NotFoundException {
        try {
            // url type example https://issues.jboss.org/rest/api/latest/filter/12322199
            SearchRestClient searchClient = restClient.getSearchClient();
            Filter filter = searchClient.getFilter(filterUrl.toURI()).get();
            return filter.getJql();
        } catch (Exception e) {
            throw new NotFoundException("Unable to retrieve filter with id:=" + filterUrl, e);
        }
    }

    /**
     * Known limitations:
     * - Jira api does not allow an issue type to be update (WTF?)
     * - Jira api does not allow project to be changed
     * @throws InterruptedException
     */
    @Override
    public boolean updateIssue(Issue issue) throws NotFoundException, AphroditeException {
        try {
            checkHost(issue.getURL());

            com.atlassian.jira.rest.client.api.domain.Issue jiraIssue = getIssue(issue);
            IssueInput update = WRAPPER.issueToFluentUpdate(issue, jiraIssue);

            restClient.getIssueClient().updateIssue(jiraIssue.getKey(), update).claim();
            if (!JiraFields.hasSameIssueStatus(issue, jiraIssue)) {
                String transition = getJiraTransition(issue, jiraIssue);
                Iterable<Transition> availableTransitions = restClient.getIssueClient().getTransitions(jiraIssue).get();
                for(Transition t : availableTransitions) {
                    if(t.getName().equals(transition)) {
                        restClient.getIssueClient().transition(jiraIssue, new TransitionInput(t.getId())).claim();
                    }
                }
            }

            // only supports add
            for(LinkIssuesInput linkIssuesInput : calculateNewLinks(issue, jiraIssue)) {
                restClient.getIssueClient().linkIssue(linkIssuesInput).claim();
            }

            return true;
        } catch (ExecutionException | InterruptedException e) {
            throw new AphroditeException(getUpdateErrorMessage(issue, e), e);
        }
    }

    private List<LinkIssuesInput> calculateNewLinks(Issue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {

        List<LinkIssuesInput> links = new ArrayList<>();

        Iterable<IssueLink> jiraIssueLinks  = jiraIssue.getIssueLinks();
        List<IssueLink> tmp = new ArrayList<>();
        jiraIssueLinks.forEach(e -> tmp.add(e));

        List<String> inbound = tmp.stream().filter(
                e -> e.getIssueLinkType().getDirection().equals(Direction.INBOUND) && e.getIssueLinkType().getName().equals("Dependency")
           ).map(e -> e.getTargetIssueKey()).collect(Collectors.toList());
        List<String> outbound = tmp.stream().filter(
                e -> e.getIssueLinkType().getDirection().equals(Direction.OUTBOUND) && e.getIssueLinkType().getName().equals("Dependency")
           ).map(e -> e.getTargetIssueKey()).collect(Collectors.toList());

        issue.getBlocks().stream().map(e -> toKey(e)).filter(e -> !e.isEmpty() && !inbound.contains(e)).forEach(e -> links.add(new LinkIssuesInput(e, issue.getTrackerId().get(), "Dependency")));
        issue.getDependsOn().stream().map(e -> toKey(e)).filter(e -> !e.isEmpty() && !outbound.contains(e)).forEach(e -> links.add(new LinkIssuesInput(issue.getTrackerId().get(), e,"Dependency")));
        return links;

    }

    private String toKey(URL url) {
        try {
            return getIssueKey(url);
        } catch (NotFoundException e) {
            return "";
        }
    }

    @Override
    public boolean addCommentToIssue(Issue issue, Comment comment) throws NotFoundException {
        super.addCommentToIssue(issue, comment);
        return postComment(issue, comment);
    }

    private boolean postComment(Issue issue, Comment comment) throws NotFoundException {
        if (comment.isPrivate())
            Utils.logWarnMessage(LOG, "Private comments are not currently supported by " + getClass().getName());
        com.atlassian.jira.rest.client.api.domain.Issue jiraIssue = getIssue(issue);

        com.atlassian.jira.rest.client.api.domain.Comment c =
                com.atlassian.jira.rest.client.api.domain.Comment.valueOf(comment.getBody());
        restClient.getIssueClient().addComment(jiraIssue.getCommentsUri(), c).claim();
        return true;
    }

    @Override
    public boolean addCommentToIssue(Map<Issue, Comment> commentMap) {

        commentMap = filterIssuesByHost(commentMap);
        if (commentMap.isEmpty())
            return true;

        commentMap.entrySet().forEach(e  -> {
            try {
                postComment(e.getKey(), e.getValue());
            } catch (NotFoundException ex) {
                LOG.error("issue not found", ex);
            }
        });
        return true;

    }

    @Override
    public boolean addCommentToIssue(Collection<Issue> issues, Comment comment) {
        issues = filterIssuesByHost(issues);
        if (issues.isEmpty())
            return true;

        Map<Issue, Comment> data = new HashMap<>();
        issues.stream().forEach(e -> data.put(e, comment));
        return addCommentToIssue(data);
    }

    @Override
    public Log getLog() {
        return LOG;
    }

    private String getIssueKey(URL url) throws NotFoundException {
        String path = url.getPath();
        boolean api = path.contains(API_ISSUE_PATH);
        boolean browse = path.contains(BROWSE_ISSUE_PATH);

        if (!(api || browse))
            throw new NotFoundException("The URL path must be of the form '" + API_ISSUE_PATH + "' OR '" + BROWSE_ISSUE_PATH + "'");

        return api ? path.substring(API_ISSUE_PATH.length()) : path.substring(BROWSE_ISSUE_PATH.length());
    }

    private String getUpdateErrorMessage(Issue issue, Exception e) {
        String msg = e.getMessage();
        if (msg.contains("does not exist or read-only")) {
            for (Map.Entry<Flag, String> entry : FLAG_MAP.entrySet()) {
                if (msg.contains(entry.getValue())) {
                    String retMsg = "Flag '%1$s' set in Issue.stage cannot be set for %2$s '%3$s'";
                    return getOptionalErrorMessage(retMsg, issue.getProduct(), entry.getKey(), issue.getURL());
                }
            }
            if (msg.contains(TARGET_RELEASE)) {
                String retMsg = "Release.milestone cannot be set for %2$s ''%3$s'";
                return getOptionalErrorMessage(retMsg, issue.getProduct(), null, issue.getURL());
            }
        }
        return null;
    }

    private String getOptionalErrorMessage(String template, Optional<?> optional, Object val, URL url) {
        if (optional.isPresent())
            return String.format(template, val, "issues in project", optional.get());
        else
            return String.format(template, val, "issue at ", url);
    }

    @Override
    public void destroy() {
        try {
            restClient.close();
        } catch (IOException e) {
            LOG.warn("destroyin jira issue tracker", e);
        }
    }
}
