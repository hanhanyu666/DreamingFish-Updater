package cn.dreamingfish.updater.management;

/** JDK HttpServer reads these process settings at its first initialization.
 * Both management and public entry points call this before creating a listener.
 */
public final class HttpServerResources {
    private HttpServerResources() {}
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
