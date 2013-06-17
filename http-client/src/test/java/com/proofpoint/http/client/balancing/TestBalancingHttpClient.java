package com.proofpoint.http.client.balancing;

import com.proofpoint.http.client.BodyGenerator;
import com.proofpoint.http.client.HttpClient;
import com.proofpoint.http.client.Request;
import com.proofpoint.http.client.RequestStats;
import com.proofpoint.http.client.Response;
import com.proofpoint.http.client.ResponseHandler;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.proofpoint.http.client.Request.Builder.preparePut;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestBalancingHttpClient
{
    private HttpServiceBalancer serviceBalancer;
    private HttpServiceAttempt serviceAttempt1;
    private HttpServiceAttempt serviceAttempt2;
    private HttpServiceAttempt serviceAttempt3;
    private BalancingHttpClient balancingHttpClient;
    private BodyGenerator bodyGenerator;
    private Request request;
    private TestingHttpClient httpClient;
    private Response response;

    @BeforeMethod
    protected void setUp()
            throws Exception
    {
        serviceBalancer = mock(HttpServiceBalancer.class);
        serviceAttempt1 = mock(HttpServiceAttempt.class);
        serviceAttempt2 = mock(HttpServiceAttempt.class);
        serviceAttempt3 = mock(HttpServiceAttempt.class);
        when(serviceBalancer.createAttempt()).thenReturn(serviceAttempt1);
        when(serviceAttempt1.getUri()).thenReturn(URI.create("http://s1.example.com"));
        when(serviceAttempt1.tryNext()).thenReturn(serviceAttempt2);
        when(serviceAttempt2.getUri()).thenReturn(URI.create("http://s2.example.com/"));
        when(serviceAttempt2.tryNext()).thenReturn(serviceAttempt3);
        when(serviceAttempt3.getUri()).thenReturn(URI.create("http://s1.example.com"));
        when(serviceAttempt3.tryNext()).thenThrow(new AssertionError("Unexpected call to serviceAttempt3.tryNext()"));
        httpClient = new TestingHttpClient("PUT");
        balancingHttpClient = new BalancingHttpClient(serviceBalancer, httpClient,
                new BalancingHttpClientConfig().setMaxAttempts(3));
        bodyGenerator = mock(BodyGenerator.class);
        request = preparePut().setUri(URI.create("v1/service")).setBodyGenerator(bodyGenerator).build();
        response = mock(Response.class);
        when(response.getStatusCode()).thenReturn(204);
    }

    @Test
    public void testSuccessfulQuery()
            throws Exception
    {
        httpClient.expectCall("http://s1.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markGood();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, bodyGenerator, response, responseHandler);
    }

    @Test
    public void testSuccessfulQueryNullPath()
            throws Exception
    {
        httpClient.expectCall("http://s1.example.com/", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        request = preparePut().setUri(new URI(null, null, null, null)).setBodyGenerator(bodyGenerator).build();
        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markGood();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, bodyGenerator, response, responseHandler);
    }

    @Test
    public void testDoesntRetryOnHandlerException()
            throws Exception
    {
        httpClient.expectCall("http://s1.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        Exception testException = new Exception("test exception");
        when(responseHandler.handle(any(Request.class), same(response))).thenThrow(testException);

        try {
            String returnValue = balancingHttpClient.execute(request, responseHandler);
            fail("expected exception, got " + returnValue);
        }
        catch (Exception e) {
            assertSame(e, testException);
        }

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, bodyGenerator, response, responseHandler);
    }

    @Test
    public void testRetryOnHttpClientException()
            throws Exception
    {
        httpClient.expectCall("http://s1.example.com/v1/service", new ConnectException());
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(serviceAttempt1).tryNext();
        verify(serviceAttempt2).getUri();
        verify(serviceAttempt2).markGood();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, bodyGenerator, response, responseHandler);
    }

    @Test
    public void testRetryOn408Status()
            throws Exception
    {
        Response response408 = mock(Response.class);
        when(response408.getStatusCode()).thenReturn(408);

        httpClient.expectCall("http://s1.example.com/v1/service", response408);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(serviceAttempt1).tryNext();
        verify(serviceAttempt2).getUri();
        verify(serviceAttempt2).markGood();
        verify(response408).getStatusCode();
        verify(response408).getHeader("X-Proofpoint-Retry");
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, bodyGenerator, response, responseHandler, response408);
    }

    @Test
    public void testRetryOn500Status()
            throws Exception
    {
        Response response500 = mock(Response.class);
        when(response500.getStatusCode()).thenReturn(500);

        httpClient.expectCall("http://s1.example.com/v1/service", response500);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(serviceAttempt1).tryNext();
        verify(serviceAttempt2).getUri();
        verify(serviceAttempt2).markGood();
        verify(response500).getStatusCode();
        verify(response500).getHeader("X-Proofpoint-Retry");
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, bodyGenerator, response, responseHandler, response500);
    }

    @Test
    public void testRetryOn502Status()
            throws Exception
    {
        Response response502 = mock(Response.class);
        when(response502.getStatusCode()).thenReturn(502);

        httpClient.expectCall("http://s1.example.com/v1/service", response502);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(serviceAttempt1).tryNext();
        verify(serviceAttempt2).getUri();
        verify(serviceAttempt2).markGood();
        verify(response502).getStatusCode();
        verify(response502).getHeader("X-Proofpoint-Retry");
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, bodyGenerator, response, responseHandler, response502);
    }

    @Test
    public void testRetryOn503Status()
            throws Exception
    {
        Response response503 = mock(Response.class);
        when(response503.getStatusCode()).thenReturn(503);

        httpClient.expectCall("http://s1.example.com/v1/service", response503);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(serviceAttempt1).tryNext();
        verify(serviceAttempt2).getUri();
        verify(serviceAttempt2).markGood();
        verify(response503).getStatusCode();
        verify(response503).getHeader("X-Proofpoint-Retry");
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, bodyGenerator, response, responseHandler, response503);
    }

    @Test
    public void testRetryOn504Status()
            throws Exception
    {
        Response response504 = mock(Response.class);
        when(response504.getStatusCode()).thenReturn(504);

        httpClient.expectCall("http://s1.example.com/v1/service", response504);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(serviceAttempt1).tryNext();
        verify(serviceAttempt2).getUri();
        verify(serviceAttempt2).markGood();
        verify(response504).getStatusCode();
        verify(response504).getHeader("X-Proofpoint-Retry");
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, bodyGenerator, response, responseHandler, response504);
    }

    @Test
    public void testNoRetryHeader()
            throws Exception
    {
        Response response500 = mock(Response.class);
        when(response500.getStatusCode()).thenReturn(500);
        when(response500.getHeader("X-Proofpoint-Retry")).thenReturn("no");

        httpClient.expectCall("http://s1.example.com/v1/service", response500);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response500))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(response500).getStatusCode();
        verify(response500).getHeader("X-Proofpoint-Retry");
        verify(responseHandler).handle(any(Request.class), same(response500));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, bodyGenerator, response, responseHandler, response500);
    }

    @Test
    public void testSuccessOnLastTry()
            throws Exception
    {
        Response response503 = mock(Response.class);
        when(response503.getStatusCode()).thenReturn(503);

        httpClient.expectCall("http://s1.example.com/v1/service", new ConnectException());
        httpClient.expectCall("http://s2.example.com/v1/service", response503);
        httpClient.expectCall("http://s1.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(serviceAttempt1).tryNext();
        verify(serviceAttempt2).getUri();
        verify(serviceAttempt2).markBad();
        verify(serviceAttempt2).tryNext();
        verify(serviceAttempt3).getUri();
        verify(serviceAttempt3).markGood();
        verify(response503).getStatusCode();
        verify(response503).getHeader("X-Proofpoint-Retry");
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, serviceAttempt3, bodyGenerator, response, responseHandler, response503);
    }

    @Test
    public void testGiveUpOnHttpClientException()
            throws Exception
    {
        Response response503 = mock(Response.class);
        when(response503.getStatusCode()).thenReturn(503);
        ConnectException connectException = new ConnectException();

        httpClient.expectCall("http://s1.example.com/v1/service", new ConnectException());
        httpClient.expectCall("http://s2.example.com/v1/service", response503);
        httpClient.expectCall("http://s1.example.com/v1/service", connectException);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        Exception testException = new Exception("test exception");
        when(responseHandler.handleException(any(Request.class), same(connectException))).thenReturn(testException);

        try {
            String returnValue = balancingHttpClient.execute(request, responseHandler);
            fail("expected exception, got " + returnValue);
        }
        catch (Exception e) {
            assertSame(e, testException);
        }

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(serviceAttempt1).tryNext();
        verify(serviceAttempt2).getUri();
        verify(serviceAttempt2).markBad();
        verify(serviceAttempt2).tryNext();
        verify(serviceAttempt3).getUri();
        verify(serviceAttempt3).markBad();
        verify(response503).getStatusCode();
        verify(response503).getHeader("X-Proofpoint-Retry");
        verify(responseHandler).handleException(any(Request.class), same(connectException));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, serviceAttempt3, bodyGenerator, response, responseHandler, response503);
    }

    @Test
    public void testGiveUpOn408Status()
            throws Exception
    {
        Response response503 = mock(Response.class);
        when(response503.getStatusCode()).thenReturn(503);
        Response response408 = mock(Response.class);
        when(response408.getStatusCode()).thenReturn(408);

        httpClient.expectCall("http://s1.example.com/v1/service", new ConnectException());
        httpClient.expectCall("http://s2.example.com/v1/service", response503);
        httpClient.expectCall("http://s1.example.com/v1/service", response408);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response408))).thenReturn("test response");

        String returnValue = balancingHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceAttempt1).getUri();
        verify(serviceAttempt1).markBad();
        verify(serviceAttempt1).tryNext();
        verify(serviceAttempt2).getUri();
        verify(serviceAttempt2).markBad();
        verify(serviceAttempt2).tryNext();
        verify(serviceAttempt3).getUri();
        // todo the mock responseHandler is swallowing the error -- verify(serviceAttempt3).markBad();
        verify(response503).getStatusCode();
        verify(response503).getHeader("X-Proofpoint-Retry");
        verify(responseHandler).handle(any(Request.class), same(response408));
        verifyNoMoreInteractions(serviceAttempt1, serviceAttempt2, /* todo serviceAttempt3,*/ bodyGenerator, response, responseHandler, response503, response408);
    }

    @Test
    public void testCreateAttemptException()
            throws Exception
    {
        serviceBalancer = mock(HttpServiceBalancer.class);
        RuntimeException balancerException = new RuntimeException("test balancer exception");
        when(serviceBalancer.createAttempt()).thenThrow(balancerException);

        balancingHttpClient = new BalancingHttpClient(serviceBalancer, httpClient,
                new BalancingHttpClientConfig().setMaxAttempts(3));

        ResponseHandler responseHandler = mock(ResponseHandler.class);
        RuntimeException handlerException = new RuntimeException("test responseHandler exception");
        when(responseHandler.handleException(any(Request.class), any(Exception.class))).thenReturn(handlerException);

        try {
            balancingHttpClient.execute(request, responseHandler);
            fail("Exception not thrown");
        }
        catch (RuntimeException e) {
            assertSame(e, handlerException, "Exception thrown by BalancingHttpClient");
        }

        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(responseHandler).handleException(same(request), captor.capture());
        assertSame(captor.getValue(), balancerException, "Exception passed to ResponseHandler");
        verifyNoMoreInteractions(responseHandler);
    }

    @Test
    public void testNextAttemptException()
            throws Exception
    {
        httpClient.expectCall("http://s1.example.com/v1/service", new ConnectException());

        serviceBalancer = mock(HttpServiceBalancer.class);
        serviceAttempt1 = mock(HttpServiceAttempt.class);
        when(serviceBalancer.createAttempt()).thenReturn(serviceAttempt1);
        when(serviceAttempt1.getUri()).thenReturn(URI.create("http://s1.example.com"));
        RuntimeException balancerException = new RuntimeException("test balancer exception");
        when(serviceAttempt1.tryNext()).thenThrow(balancerException);

        balancingHttpClient = new BalancingHttpClient(serviceBalancer, httpClient,
                new BalancingHttpClientConfig().setMaxAttempts(3));

        ResponseHandler responseHandler = mock(ResponseHandler.class);
        RuntimeException handlerException = new RuntimeException("test responseHandler exception");
        when(responseHandler.handleException(any(Request.class), any(Exception.class))).thenReturn(handlerException);

        try {
            balancingHttpClient.execute(request, responseHandler);
            fail("Exception not thrown");
        }
        catch (RuntimeException e) {
            assertSame(e, handlerException, "Exception thrown by BalancingHttpClient");
        }

        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(responseHandler).handleException(same(request), captor.capture());
        assertSame(captor.getValue(), balancerException, "Exception passed to ResponseHandler");
        verifyNoMoreInteractions(responseHandler);
    }

    @Test
    public void testGetStats()
    {
        RequestStats requestStats = new RequestStats();
        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.getStats()).thenReturn(requestStats);

        balancingHttpClient = new BalancingHttpClient(serviceBalancer, mockClient, new BalancingHttpClientConfig());
        assertSame(balancingHttpClient.getStats(), requestStats);

        verify(mockClient).getStats();
        verifyNoMoreInteractions(mockClient, serviceBalancer);
    }

    @Test
    public void testClose()
    {
        HttpClient mockClient = mock(HttpClient.class);

        balancingHttpClient = new BalancingHttpClient(serviceBalancer, mockClient, new BalancingHttpClientConfig());
        balancingHttpClient.close();

        verify(mockClient).close();
        verifyNoMoreInteractions(mockClient, serviceBalancer);
    }

    @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".* is not a relative URI")
    public void testUriWithScheme()
            throws Exception
    {
        request = preparePut().setUri(new URI("http", null, "/v1/service", null)).setBodyGenerator(bodyGenerator).build();
        balancingHttpClient.execute(request, mock(ResponseHandler.class));
    }

    @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".* has a host component")
    public void testUriWithHost()
            throws Exception
    {
        request = preparePut().setUri(new URI(null, "example.com", "v1/service", null)).setBodyGenerator(bodyGenerator).build();
        balancingHttpClient.execute(request, mock(ResponseHandler.class));
    }

    @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".* path starts with '/'")
    public void testUriWithAbsolutePath()
            throws Exception
    {
        request = preparePut().setUri(new URI(null, null, "/v1/service", null)).setBodyGenerator(bodyGenerator).build();
        balancingHttpClient.execute(request, mock(ResponseHandler.class));
    }

    class TestingHttpClient implements HttpClient
    {

        private String method;
        private List<URI> uris = new ArrayList<>();
        private List<Object> responses = new ArrayList<>();

        TestingHttpClient(String method)
        {
            this.method = method;
            checkArgument(uris.size() == responses.size(), "uris same size as responses");
        }

        TestingHttpClient expectCall(String uri, Response response)
        {
            return expectCall(URI.create(uri), response);
        }

        TestingHttpClient expectCall(String uri, Exception exception)
        {
            return expectCall(URI.create(uri), exception);
        }

        private TestingHttpClient expectCall(URI uri, Object response)
        {
            uris.add(uri);
            responses.add(response);
            return this;
        }

        void assertDone()
        {
            assertEquals(uris.size(), 0, "all expected calls made");
        }

        @Override
        public <T, E extends Exception> T execute(Request request, ResponseHandler<T, E> responseHandler)
                throws E
        {
            assertTrue(uris.size() > 0, "call was expected");
            assertEquals(request.getMethod(), method, "request method");
            assertEquals(request.getUri(), uris.remove(0), "request uri");
            assertEquals(request.getBodyGenerator(), bodyGenerator, "request body generator");

            Object response = responses.remove(0);
            if (response instanceof Exception) {
                throw responseHandler.handleException(request, (Exception) response);
            }
            return responseHandler.handle(request, (Response) response);
        }

        @Override
        public RequestStats getStats()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close()
        {
            throw new UnsupportedOperationException();
        }
    }
}
