package cn.dreamingfish.updater.management;

/** JDK HttpServer reads these process settings at its first initialization.
 * Both management and public entry points call this before creating a listener.
 */
public final class HttpServerResources {
    private HttpServerResources() {}

    /** With automatic draining disabled, advertise reuse only after the JDK's
     * request stream has actually reached EOF. Even a zero-length request needs
     * one read on Java 21 to set that flag. Never drain a rejected upload. */
    public static com.sun.net.httpserver.Filter connectionLifecycle() {
        return new com.sun.net.httpserver.Filter() {
            @Override public String description() { return "Bounded request bodies and truthful connection reuse"; }
            @Override public void doFilter(com.sun.net.httpserver.HttpExchange exchange, Chain chain)
                    throws java.io.IOException {
                var response=exchange.getResponseHeaders();
                response.set("Connection","close");
                boolean clientCloses=exchange.getRequestHeaders().getOrDefault("Connection",java.util.List.of())
                        .stream().anyMatch(value->java.util.Arrays.stream(value.split(","))
                                .anyMatch(token->token.trim().equalsIgnoreCase("close")));
                var input=new java.io.FilterInputStream(exchange.getRequestBody()) {
                    private int checked(int count) {
                        if(count==-1 && !clientCloses) response.remove("Connection");
                        return count;
                    }
                    @Override public int read() throws java.io.IOException { return checked(in.read()); }
                    @Override public int read(byte[] bytes,int offset,int length) throws java.io.IOException {
                        return checked(in.read(bytes,offset,length));
                    }
                };
                exchange.setStreams(input,null);
                String length=exchange.getRequestHeaders().getFirst("Content-Length");
                if(exchange.getRequestHeaders().getFirst("Transfer-Encoding")==null
                        && (length==null || length.equals("0"))) input.read();
                chain.doFilter(exchange);
            }
        };
    }
    public static synchronized void configure() {
        java.util.Map.of("jdk.httpserver.maxConnections","256",
                "sun.net.httpserver.maxIdleConnections","32",
                "sun.net.httpserver.idleInterval","15",
                "sun.net.httpserver.maxReqHeaders","100",
                "sun.net.httpserver.maxReqHeaderSize","32768",
                "sun.net.httpserver.drainAmount","0",
                "sun.net.httpserver.maxReqTime","3600").forEach((key,value)->{
            if(System.getProperty(key)==null)System.setProperty(key,value);
        });
        bounded("jdk.httpserver.maxConnections",32,4096);
        bounded("sun.net.httpserver.maxIdleConnections",0,4096);
        bounded("sun.net.httpserver.idleInterval",1,3600);
        bounded("sun.net.httpserver.maxReqHeaders",16,1024);
        bounded("sun.net.httpserver.maxReqHeaderSize",1024,1024*1024);
        bounded("sun.net.httpserver.maxReqTime",1,86400);
        bounded("sun.net.httpserver.drainAmount",0,0);
    }
    private static void bounded(String property,long minimum,long maximum){
        try{long value=Long.parseLong(System.getProperty(property));if(value<minimum||value>maximum)throw new NumberFormatException();}
        catch(NumberFormatException error){throw new ManagementException(property+" 必须位于 "+minimum+"–"+maximum+"；不能关闭服务资源上限");}
    }
}
