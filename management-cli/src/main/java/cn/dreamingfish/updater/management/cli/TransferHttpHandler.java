package cn.dreamingfish.updater.management.cli;
import com.sun.net.httpserver.HttpExchange;
import java.nio.file.Path;
import java.util.*;
import static cn.dreamingfish.updater.management.cli.AdminWebServer.*;

/** Source transfer protocol adapter. Authorization and exception mapping remain centralized. */
final class TransferHttpHandler {
    private final AdminWebServer io;private final ManagementCli root;
    TransferHttpHandler(AdminWebServer io,ManagementCli root){this.io=io;this.root=root;}
    boolean handle(HttpExchange exchange,String projectId,String action)throws Exception {
        switch(action){
                case "stage" -> {
                    if (exchange.getRequestMethod().equals("DELETE")) {
                        boolean discarded=root.services().transfers().discard(projectId, required(query(exchange.getRequestURI(), "id"), "暂存 ID"));
                        io.sendJson(exchange, 200, Map.of("discarded",discarded,"protected",!discarded));
                    } else {
                        requireMethod(exchange, "PUT");
                        requireBinary(exchange);
                        // Receiving an upload is bounded by its own lease and upload lock;
                        // it does not hold the HTTP/global mutation gate while the client sends.
                        io.sendJson(exchange,201,root.services().transfers().stage(projectId,required(query(exchange.getRequestURI(),"path"),"目标路径"),exchange.getRequestBody(),contentLength(exchange),query(exchange.getRequestURI(),"requestId")));
                    }
                }
                case "recheck" -> {
                    requireMethod(exchange, "POST");
                    IdRequest request = io.readJson(exchange, IdRequest.class);
                    if (!root.services().transfers().hasStage(projectId, required(request.id, "暂存 ID")))
                        throw new AdminWebServer.WebApiException(410, "stage_expired", "暂存已过期或上传未完成；未发现提交记录，请重新选择或上传");
                    io.sendJson(exchange, 200, root.services().transfers().plan(projectId, request.id));
                }
                case "stage-server" -> {
                    requireMethod(exchange, "POST");
                    ServerStageRequest request = io.readJson(exchange, ServerStageRequest.class);
                    io.sendJson(exchange, 201, io.mutate(() -> root.services().transfers().stageServer(projectId,
                            Path.of(required(request.sourcePath, "服务器文件路径")), required(request.targetPath, "目标路径"), request.requestId)));
                }
                case "commit" -> {
                    requireMethod(exchange, "POST");
                    CommitRequest request = io.readJson(exchange, CommitRequest.class);
                    io.sendJson(exchange, 200, io.mutate(() -> root.services().transfers().commit(projectId,
                            required(request.id, "暂存 ID"), commitAction(request.action), required(request.stamp, "检查凭据"), false)));
                }
                case "check-imports" -> {
                    requireMethod(exchange, "POST");
                    ImportCheckRequest request = io.readJson(exchange, ImportCheckRequest.class);
                    io.sendJson(exchange, 200, io.mutate(() -> io.previewView(root.services().transfers().checkImports(projectId, request.ids))));
                }
                case "import-history" -> {
                    requireMethod(exchange, "GET");
                    io.sendJson(exchange, 200, root.services().transfers().history(projectId));
                }
                case "undo-import" -> {
                    requireMethod(exchange, "POST");
                    IdRequest request = io.readJson(exchange, IdRequest.class);
                    io.sendJson(exchange, 200, io.mutate(() -> root.services().transfers().undo(projectId, required(request.id, "导入 ID"))));
                }
            default->{return false;}
        }
        return true;
    }
    private static String commitAction(CommitAction action){if(action==null)throw new cn.dreamingfish.updater.management.ManagementException("请选择新增、覆盖或替换旧模组");return action.name();}
    enum CommitAction {ADD,OVERWRITE,REPLACE_MOD}
    private record CommitRequest(String id,CommitAction action,String stamp){}
    private record IdRequest(String id){}
    private record ServerStageRequest(String sourcePath,String targetPath,String requestId){}
    private record ImportCheckRequest(List<String> ids){}
}
