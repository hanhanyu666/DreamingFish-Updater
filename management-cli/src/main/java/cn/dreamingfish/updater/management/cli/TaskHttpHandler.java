package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.*;
import cn.dreamingfish.updater.protocol.*;
import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.util.*;

/** Authenticated HTTP adapter for durable application tasks; legacy requests remain synchronous. */
final class TaskHttpHandler {
    private static final ThreadLocal<Boolean> DETACHED=ThreadLocal.withInitial(()->false);
    private final ManagementCli root;private final HttpHandler dispatch;
    TaskHttpHandler(ManagementCli root,HttpHandler dispatch){this.root=root;this.dispatch=dispatch;}
    boolean handle(HttpExchange exchange,String path)throws Exception {
        var services=root.services();var tasks=services.tasks();var json=services.json();
        if(path.equals("/api/tasks")&&exchange.getRequestMethod().equals("GET")){send(exchange,200,Map.of("tasks",tasks.list(),"active",tasks.active(),"queued",tasks.queued(),"cachedRecords",tasks.cachedRecords(),"cachedBytes",tasks.cachedBytes()),json);return true;}
        if(path.startsWith("/api/tasks/")){
            String id=path.substring("/api/tasks/".length());
            if(exchange.getRequestMethod().equals("DELETE"))send(exchange,200,tasks.cancel(id),json);
            else if(exchange.getRequestMethod().equals("GET")){
                var task=tasks.require(id);
                if(task.state()==ApplicationTasks.State.UNKNOWN&&task.kind().equals("files/commit")&&task.sourceRequestId()!=null){
                    if(services.transfers().hasStage(task.projectId(),task.sourceRequestId())){
                        var plan=services.transfers().plan(task.projectId(),task.sourceRequestId());
                        if(plan.committed()||plan.undone())task=tasks.resolved(id,new SourceTransferService.Result(plan.id(),plan.path(),plan.undone(),""));
                    }
                }
                send(exchange,200,task,json);
            }else throw new ApplicationTasks.Failure(405,"任务入口只允许 GET / DELETE");
            return true;
        }
        if(DETACHED.get()||!"respond-async".equals(exchange.getRequestHeaders().getFirst("Prefer"))||!eligible(path,exchange.getRequestMethod()))return false;
        String id=exchange.getRequestHeaders().getFirst("X-DFS-Request-Id");if(id==null)throw new ManagementException("异步任务需要稳定的 X-DFS-Request-Id");
        byte[] body=exchange.getRequestBody().readNBytes(1024*1024+1);if(body.length>1024*1024)throw new ManagementException("任务请求超过 1 MiB");
        JsonNode input=json.read(body,JsonNode.class);
        String[] parts=path.split("/");String project=parts.length>3&&parts[2].equals("projects")?URLDecoder.decode(parts[3],java.nio.charset.StandardCharsets.UTF_8):"";
        String kind=project.isEmpty()?path.substring(5):String.join("/",Arrays.copyOfRange(parts,4,parts.length));
        boolean credentials=kind.equals("distribution-webdav")||kind.equals("distribution-s3");
        String sourceId=input.hasNonNull("id")?input.get("id").asText():null;
        CapturedExchange captured=new CapturedExchange(exchange,body);
        var snapshot=tasks.submit(id,project,kind,sourceId,CryptoSupport.sha256(body),credentials,()->{
            DETACHED.set(true);
            try {
                dispatch.handle(captured);
                JsonNode result=json.read(captured.output.toByteArray(),JsonNode.class);
                if(captured.status>=400)throw new ApplicationTasks.Failure(captured.status,result.path("message").asText("管理任务失败"));
                return result;
            }finally{DETACHED.remove();}
        });
        send(exchange,202,Map.of("taskId",snapshot.id(),"state",snapshot.state()),json);return true;
    }
    private static boolean eligible(String path,String method){
        if(!method.equals("POST"))return false;
        return path.matches("/api/projects/[^/]+/(scan|publish|rollback|programs|program-package|instance|deployment|distribution-export|distribution-webdav|distribution-s3|files/(commit|stage-server|check-imports|undo-import))")
                ||path.equals("/api/storage/clean");
    }
    private static void send(HttpExchange exchange,int status,Object result,JsonCodec json)throws IOException {
        byte[] bytes=json.write(result);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);try(OutputStream output=exchange.getResponseBody()){output.write(bytes);}
    }
    private static final class CapturedExchange extends HttpExchange {
        final HttpContext context;final URI uri;final String method,protocol;final InetSocketAddress remote,local;final HttpPrincipal principal;
        final Map<String,Object> attributes=new HashMap<>();final Headers request=new Headers(),response=new Headers();final InputStream input;int status=200;
        final ByteArrayOutputStream output=new ByteArrayOutputStream(){@Override public synchronized void write(byte[] bytes,int start,int length){if(count+length>16*1024*1024)throw new ManagementException("任务结果超过 16 MiB；请在项目内查询实际结果");super.write(bytes,start,length);}};
        CapturedExchange(HttpExchange original,byte[] body){context=original.getHttpContext();uri=original.getRequestURI();method=original.getRequestMethod();protocol=original.getProtocol();remote=original.getRemoteAddress();local=original.getLocalAddress();principal=original.getPrincipal();original.getRequestHeaders().forEach((name,values)->request.put(name,List.copyOf(values)));input=new ByteArrayInputStream(body);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}
        public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}
        public HttpContext getHttpContext(){return context;}public void close(){}
        public InputStream getRequestBody(){return input;}public OutputStream getResponseBody(){return output;}
        public void sendResponseHeaders(int code,long length){status=code;}public InetSocketAddress getRemoteAddress(){return remote;}
        public int getResponseCode(){return status;}public InetSocketAddress getLocalAddress(){return local;}
        public String getProtocol(){return protocol;}public Object getAttribute(String name){return attributes.get(name);}
        public void setAttribute(String name,Object value){attributes.put(name,value);}public void setStreams(InputStream input,OutputStream output){throw new UnsupportedOperationException();}
        public HttpPrincipal getPrincipal(){return principal;}
    }
}
