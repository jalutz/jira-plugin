package hudson.plugins.jira;

import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.Transition;
import com.google.common.collect.Sets;
import hudson.model.*;
import hudson.scm.ChangeLogSet;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.apache.commons.lang.RandomStringUtils.randomNumeric;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.*;

/**
 * User: lanwen
 * Date: 10.09.13
 * Time: 0:57
 */
@RunWith(MockitoJUnitRunner.class)
public class ChangingWorkflowTest {

    public static final String NON_EMPTY_COMMENT = "Non empty comment";
    private final String ISSUE_JQL = "jql";
    private final String NON_EMPTY_WORKFLOW_LOWERCASE = "workflow";

    @Mock
    private JiraSite site;

    @Mock
    private JiraRestService restService;

    @Mock
    private JiraSession mockSession;
    
    @Mock
    private TaskListener mockTaskListener;

    private AbstractBuild mockRun;

    private JiraSession spySession;

    private static class MockEntry extends ChangeLogSet.Entry {

        private final String msg;

        public MockEntry(String msg) {
            this.msg = msg;
        }

        @Override
        public Collection<String> getAffectedPaths() {
            return null;
        }

        @Override
        public User getAuthor() {
            return null;
        }

        @Override
        public String getMsg() {
            return this.msg;
        }
    }

    @Before
    public void setupSpy() throws Exception {
        JiraSession session = new JiraSession(site, restService);
        spySession = spy(session);

        Issue mockIssue = mock(Issue.class);
        when(mockIssue.getKey()).thenReturn("ABC-1");
        when(mockSession.getIssue(anyString())).thenReturn(mockIssue);

        JiraSite site = mock(JiraSite.class);
        when(site.getSession()).thenReturn(session);
        when(site.getIssuePattern()).thenReturn(Pattern.compile("(TR-[0-9]*)"));

        mockRun = mock(AbstractBuild.class);
        mockTaskListener = mock(TaskListener.class);

        when(mockTaskListener.getLogger()).thenReturn(System.out);

        ChangeLogSet changeLogSet = mock(ChangeLogSet.class);

        Set<? extends ChangeLogSet.Entry> entries = Sets.newHashSet(
                new MockEntry("Fixed JI123-4711"),
                new MockEntry("Fixed foo_bar-4710"),
                new MockEntry("Fixed FoO_bAr-4711"),
                new MockEntry("Fixed something.\nJFoO_bAr_MULTI-4718"),
                new MockEntry("TR-123: foo"),
                new MockEntry("[ABC-42] hallo"),
                new MockEntry("#123: this one must not match"),
                new MockEntry("ABC-: this one must also not match"),
                new MockEntry("ABC-: \n\nABC-127:\nthis one should match"),
                new MockEntry("ABC-: \n\nABC-128:\nthis one should match"),
                new MockEntry("ABC-: \n\nXYZ-10:\nXYZ-20 this one too"),
                new MockEntry("Fixed DOT-4."),
                new MockEntry("Fixed DOT-5. Did it right this time"));

        when(changeLogSet.iterator()).thenReturn(entries.iterator());

        when(mockRun.getChangeSet()).thenReturn(changeLogSet);

        Job mockJob = mock(Job.class);
        when(mockRun.getParent()).thenReturn(mockJob);

        JiraProjectProperty jiraProjectProperty = mock(JiraProjectProperty.class);
        when(jiraProjectProperty.getSite()).thenReturn(site);
        when(mockJob.getProperty(JiraProjectProperty.class)).thenReturn(jiraProjectProperty);
    }

    @Test
    public void onGetActionItInvokesServiceMethod() throws Exception {
        spySession.getActionIdForIssue(ISSUE_JQL, NON_EMPTY_WORKFLOW_LOWERCASE);
        verify(restService, times(1)).getAvailableActions(eq(ISSUE_JQL));
    }

    @Test
    public void getActionIdReturnsNullWhenServiceReturnsNull() throws Exception {
        when(restService.getAvailableActions(ISSUE_JQL)).thenReturn(null);
        assertThat(spySession.getActionIdForIssue(ISSUE_JQL, NON_EMPTY_WORKFLOW_LOWERCASE), nullValue());
    }


    @Test
    public void getActionIdIteratesOverAllActionsEvenOneOfNamesIsNull() throws Exception {
        Transition action1 = mock(Transition.class);
        Transition action2 = mock(Transition.class);

        when(action1.getName()).thenReturn(null);
        when(action2.getName()).thenReturn("name");

        when(restService.getAvailableActions(ISSUE_JQL)).thenReturn( Arrays.asList(action1, action2));
        assertThat(spySession.getActionIdForIssue(ISSUE_JQL, NON_EMPTY_WORKFLOW_LOWERCASE), nullValue());

        verify(action1, times(1)).getName();
        verify(action2, times(2)).getName(); // one for null check, other for equals
    }

    @Test
    public void getActionIdReturnsNullWhenNullWorkflowUsed() throws Exception {
        String workflowAction = null;
        Transition action1 = mock(Transition.class);
        when(action1.getName()).thenReturn("name");

        when(restService.getAvailableActions(ISSUE_JQL)).thenReturn( Collections.singletonList(action1));
        assertThat(spySession.getActionIdForIssue(ISSUE_JQL, workflowAction), nullValue());
    }

    @Test
    public void getActionIdReturnsIdWhenFoundIgnorecaseWorkflow() throws Exception {
        String id = randomNumeric(5);
        Transition action1 = mock(Transition.class);
        when(action1.getName()).thenReturn(NON_EMPTY_WORKFLOW_LOWERCASE.toUpperCase());
        when(restService.getAvailableActions(ISSUE_JQL)).thenReturn(Arrays.asList(action1));
        when(action1.getId()).thenReturn(Integer.valueOf(id));

        assertThat(spySession.getActionIdForIssue(ISSUE_JQL, NON_EMPTY_WORKFLOW_LOWERCASE), equalTo(Integer.valueOf(id)));
    }


    @Test
    public void addCommentsOnNonEmptyWorkflowAndNonEmptyComment() throws IOException, TimeoutException {
        when(site.getSession()).thenReturn(mockSession);
        when(mockSession.getIssuesFromJqlSearch(anyString())).thenReturn(Arrays.asList(mock(Issue.class)));
        when(mockSession.getActionIdForIssue(anyString(),
                eq(NON_EMPTY_WORKFLOW_LOWERCASE))).thenReturn(Integer.valueOf(randomNumeric(5)));
        when(site.progressMatchingIssues(anyString(), anyString(), anyString(), Matchers.any(PrintStream.class)))
                .thenCallRealMethod();

        site.progressMatchingIssues(ISSUE_JQL,
                NON_EMPTY_WORKFLOW_LOWERCASE, NON_EMPTY_COMMENT, mock(PrintStream.class));

        verify(mockSession, times(1)).addComment(anyString(), eq(NON_EMPTY_COMMENT),
                isNull(String.class), isNull(String.class));
        verify(mockSession, times(1)).progressWorkflowAction(anyString(), anyInt());
    }


    @Test
    public void addCommentsOnNullWorkflowAndNonEmptyComment() throws IOException, TimeoutException {
        when(site.getSession()).thenReturn(mockSession);
        when(mockSession.getIssuesFromJqlSearch(anyString())).thenReturn(Arrays.asList(mock(Issue.class)));
        when(site.progressMatchingIssues(anyString(), anyString(), anyString(), Matchers.any(PrintStream.class)))
                .thenCallRealMethod();

        site.progressMatchingIssues(ISSUE_JQL, null, NON_EMPTY_COMMENT, mock(PrintStream.class));

        verify(mockSession, times(1)).addComment(anyString(), eq(NON_EMPTY_COMMENT),
                isNull(String.class), isNull(String.class));
    }


    @Test
    public void dontAddCommentsOnNullWorkflowAndNullComment() throws IOException, TimeoutException {
        when(site.getSession()).thenReturn(mockSession);
        when(mockSession.getIssuesFromJqlSearch(anyString())).thenReturn(Arrays.asList(mock(Issue.class)));
        when(site.progressMatchingIssues(anyString(), anyString(), anyString(), Matchers.any(PrintStream.class)))
                .thenCallRealMethod();

        site.progressMatchingIssues(ISSUE_JQL, null, null, mock(PrintStream.class));

        verify(mockSession, never()).addComment(anyString(), anyString(), isNull(String.class), isNull(String.class));
    }


}
