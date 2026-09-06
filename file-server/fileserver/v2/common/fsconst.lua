-- "message-chat" (service goc tu du an cu lego-new) khong ton tai trong repo pingo -- hall dung vai
-- thay the, tu them 3 route nay (xem HallApiHandlers#createFile/updateFile/getFile), tro thang toi
-- Service Kubernetes "hall" (helm/templates/services.yaml, port 8085) thay vi service ao "jad".
local _M = {
    jad_file = "http://hall.default.svc.cluster.local:8085/file/create",
    jad_file_get = "http://hall.default.svc.cluster.local:8085/file/get",
    jad_files = os.getenv('V1_FILES'),
    jad_update_done_uri = "http://hall.default.svc.cluster.local:8085/file/update",
    server_id = os.getenv('SERVER_ID'),
}
return _M
