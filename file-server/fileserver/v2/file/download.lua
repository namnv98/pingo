local utils = require "utils"
local jad = require "jad"

local forward_error = utils.forward_error

local args, err21 = ngx.req.get_uri_args()
local org_id = ngx.var.http_orgid;

local file_resp, err = jad.get_file_path(org_id, ngx.var.http_authorization, args)
if err then
    forward_error(ngx.HTTP_OK, err)
    return
end

local file_id = file_resp.data.id;
local file_path = file_resp.data.path;

ngx.header.content_type = file_resp.data.mime
ngx.header["X-File-Name"] = file_resp.data.name

ngx.req.set_uri("/" .. file_path .. file_id)
ngx.exec("@sendfile")
