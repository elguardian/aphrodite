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

import org.jboss.set.aphrodite.domain.FlagStatus;
import org.jboss.set.aphrodite.domain.SearchCriteria;

import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.CUSTOM_FIELD_MAP;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.TARGET_RELEASE;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.getJQLField;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.getJiraStatus;

/**
 * @author Ryan Emerson
 */
class JiraQueryBuilder {

    // TODO add streams query support when implemented in JIRA
    String getSearchJQL(SearchCriteria criteria) {
        StringBuilder sb = new StringBuilder();
        criteria.getStatus().ifPresent(status -> addCriteriaToJQL("status = ", getJiraStatus(status), " AND ", sb));
        // JIRA Cloud requires email addresses in quotes for user fields ("rchakrab@redhat.com")
        criteria.getAssignee().ifPresent(assignee -> addUserCriteriaToJQL("assignee", assignee, " AND ", sb));
        criteria.getReporter().ifPresent(reporter -> addUserCriteriaToJQL("reporter", reporter, " AND ", sb));
        criteria.getComponent().ifPresent(component -> addCriteriaToJQL("component = ", component, " AND ", sb));
        criteria.getProduct().ifPresent(product -> addCriteriaToJQL("project = ", product, " AND ", sb));
        criteria.getStartDate().ifPresent(date -> {
            String formattedDate = date.atStartOfDay().format((DateTimeFormatter.ISO_LOCAL_DATE));
            addCriteriaToJQL("updated >= ", formattedDate, " AND ", sb);
        });

        criteria.getRelease().ifPresent(release -> {
            if (release.getVersion().isPresent()) {
                addCriteriaToJQL("fixVersion = ", release.getVersion().orElse(null), " AND ", sb);
            } else {
                sb.append(" AND fixVersion is EMPTY");
            }
            addCriteriaToJQL(getJQLField(TARGET_RELEASE) + " = ", release.getMilestone().orElse(null), " AND ", sb);
        });

        criteria.getStage().ifPresent(
                stage -> stage.getStateMap().entrySet()
                        .stream()
                        .filter(entry -> entry.getValue() != FlagStatus.NO_SET)
                        .forEach(entry ->
                                addCriteriaToJQL(CUSTOM_FIELD_MAP.get(entry.getKey().toString()) + " = ",
                                        entry.getValue().getSymbol(), " AND ", sb)));

        criteria.getLabels().forEach(label->addCriteriaToJQL("labels = ", label, " AND ", sb));
        return sb.toString();
    }

    String getSearchJQLFromTo(SearchCriteria criteria) {
        StringBuilder sb = new StringBuilder();
        addCriteriaToJQL("project = ", criteria.getProduct().orElse(null), " AND ", sb);
        addCriteriaToJQL("fixVersion = ", criteria.getRelease().get().getVersion().orElse(null), " AND ", sb);
        criteria.getStartDate().ifPresent(startDate -> {
            addCriteriaToJQL("fixVersion was not ", criteria.getRelease().get().getVersion().orElse(null), " AND ", sb);
            String formattedDate = startDate.atStartOfDay().format((DateTimeFormatter.ISO_LOCAL_DATE));
            addCriteriaToJQL("", formattedDate, " BEFORE ", sb);
        });
        criteria.getEndDate().ifPresent(endDate -> {
            addCriteriaToJQL("fixVersion was ", criteria.getRelease().get().getVersion().orElse(null), " AND ", sb);
            String formattedDate = endDate.atStartOfDay().format((DateTimeFormatter.ISO_LOCAL_DATE));
            addCriteriaToJQL("", formattedDate, " BEFORE ", sb);
        });
        return sb.toString();
    }

    String getMultipleIssueJQL(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        ids.forEach(id -> addCriteriaToJQL("id = ", id, " OR ", sb));
        return sb.toString();
    }

    private void addCriteriaToJQL(String criteria, Object value, String connector, StringBuilder sb) {
        if (criteria == null || value == null)
            return;

        if (connector != null && sb.length() != 0)
            sb.append(connector);

        sb.append(criteria);
        sb.append("'");
        sb.append(value);
        sb.append("'");
    }

    /**
     * JQL that queries by username will return 0 results on Jira Cloud.
     * It must be adapted to query by account ID or quoted strings of account emails.
     * Details: https://docs.google.com/document/d/1tpf_XJB1MiqkwFSKI02fa_KWkXFsarN6zVfj1-jl2g8/edit?usp=sharing
     */
    private void addUserCriteriaToJQL(String field, String value, String connector, StringBuilder sb) {
        if (field == null || value == null)
            return;

        if (connector != null && sb.length() != 0)
            sb.append(connector);

        sb.append(field);
        sb.append(" in (");

        if (value.contains("@")) {
            // Email format: needs quotes
            sb.append("\"");
            sb.append(value);
            sb.append("\"");
        } else {
            // AccountId format: no quotes
            sb.append(value);
        }
        sb.append(")");
    }
}
