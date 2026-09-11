local cjson = require "cjson"
local lfs = require("lfs")
local upload = require "resty.upload"
local utils = require "utils"
local jad = require "jad"

ngx.header.content_type = "application/json; charset=utf-8"

local response_error = utils.response_error
local forward_error = utils.forward_error

local args, err21 = ngx.req.get_uri_args()
local org_id = ngx.var.http_orgid;

local file_resp, err = jad.create_file_path(org_id, ngx.var.http_authorization, args)
if err then
    forward_error(ngx.HTTP_OK, err)
    return
end

local function create_directory_recursive(directory_path)
    -- Thử tạo thư mục
    local success, err = lfs.mkdir(directory_path)
    if success then
        return true
    elseif err == "File exists" then
        return true
    elseif err == "No such file or directory" then
        -- Thử tạo thư mục cha trước đó
        local parent_directory = directory_path:match("(.+)/[^/]*$")
        if parent_directory then
            if not create_directory_recursive(parent_directory) then
                return false, "Failed to create parent directory"
            end
            -- Sau khi tạo thư mục cha, thử tạo lại thư mục con
            return create_directory_recursive(directory_path)
        else
            return false, "Invalid directory path"
        end
    else
        return false, err
    end
end

local upload_folder = "/home/pingo/v2/"
local file_path = upload_folder .. file_resp.data.path
local file_id = file_resp.data.id
local ok, err1 = create_directory_recursive(file_path)
if err1 then
    response_error(ngx.HTTP_INTERNAL_SERVER_ERROR, err1)
    return
end


local form, err2 = upload:new(4096)

if not form then
    response_error(ngx.HTTP_OK, "form is required")
    return
end

local file = io.open(file_path .. file_id, "w+")
-- ngx.var.arg_fileMime la bien nginx THO (KHONG tu giai ma %-encoding, khac voi args['fileMime']
-- lay tu ngx.req.get_uri_args() o dau file -- da tu giai ma san). Dung nham bien tho khien
-- "video/mp4" client gui len (encodeURIComponent -> "video%2Fmp4") bi luu THANG vao DB dang
-- "video%2Fmp4", lam sai mime -> get-thumbnail.lua khong nhan ra la video/anh nua. Da gap that su.
local content_type = args['fileMime']

while true do
    local typ, res, err = form:read()
    if not typ then
        response_error(ngx.HTTP_OK, err)
        return
    end
    if typ == "body" then
        file:write(res)
    elseif typ == "part_end" then
        break
    elseif typ == "eof" then
        break
    else
        -- do nothing
    end
end

-- Get file size
local current = file:seek()      -- get current position
local upload_file_size = file:seek("end")    -- get file size
file:seek("set", current)        -- restore position
file:close()
file = nil

args['userId'] = file_resp['userId']
args['orgId'] = org_id
local update_resp, err4 = jad.upload_done({
    fileId = file_id,
    size = upload_file_size,
    mime = content_type,
}, args)

if err4 then
    ngx.log(ngx.ERR, err4)
    forward_error(ngx.HTTP_INTERNAL_SERVER_ERROR, err4)
    return
end

ngx.status = 200
ngx.say(cjson.encode({
    id = file_resp.data.id,
    size = upload_file_size,
    mime = content_type
}))
