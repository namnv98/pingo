local utils = require "utils"
local jad = require "jad"

ngx.header.content_type = "application/json; charset=utf-8"

local forward_error = utils.forward_error
local starts_with = utils.starts_with
local split = utils.split
local not_found = utils.return_not_found

local args, err21 = ngx.req.get_uri_args()
local org_id = ngx.var.http_orgid;
local size = ngx.var.arg_size;

local file_resp, err = jad.get_file_path(org_id, ngx.var.http_authorization, args)
if err then
    forward_error(ngx.HTTP_OK, err)
    return
end

local file_name = file_resp.data.id
local file_path = file_resp.data.path
local file_mime = file_resp.data.mime

local source_path = "/home/luklak/v2/" .. file_path
local source_fname = source_path .. file_name

-- make sure the file exists
local file = io.open(source_fname)
if not file then
    not_found(source_fname)
end
file:close()

--local thumbnail_file_name = file_name .. "_" .. size .. ".png"
local thumbnail_file_name
if starts_with(file_mime, "image/gif") then
    thumbnail_file_name = file_name .. "_" .. size .. ".gif"
else
    thumbnail_file_name = file_name .. "_" .. size .. ".png"
end

local dest_fname = source_path .. thumbnail_file_name


local ffi = require("ffi")

-- Define the necessary FFmpeg functions
ffi.cdef[[
    int avformat_open_input(void **ps, const char *filename, void *fmt, void **options);
    int avformat_find_stream_info(void *ic, void **options);
    int avcodec_open2(void *avctx, void *codec, void **options);
    int av_read_frame(void *s, void *pkt);
    int avcodec_decode_video2(void *avctx, void *picture, int *got_picture_ptr, const void *avpkt);
    void av_free_packet(void *pkt);
    void avformat_close_input(void **s);
]]

-- Define the function to create thumbnail from video using FFmpeg
function createThumbnailWithFFmpeg(videoPath, outputPath, time, thumbnailSize)
    -- "scale=WxH" TRẦN (ban đầu) ép đúng WxH tuyệt đối -- BÓP MÉO video không vuông (đa số video
    -- thật là 16:9/9:16, không phải 1:1) vì ffmpeg không tự giữ tỷ lệ khi dùng dạng rút gọn "WxH".
    -- Tách W/H rồi dùng force_original_aspect_ratio=decrease -- co vừa trong khung WxH, GIỮ ĐÚNG tỷ
    -- lệ gốc (viền dư nếu có do phía hiển thị/CSS lo, không crop/méo ảnh gốc).
    local w, h = thumbnailSize:match("^(%d+)x(%d+)$")
    local scaleFilter = (w and h)
        and string.format("scale='min(%s,iw)':'min(%s,ih)':force_original_aspect_ratio=decrease", w, h)
        or string.format("scale=%s", thumbnailSize) -- size khong dung dang "WxH" -- giu hanh vi cu, khong doan them
    local cmd = string.format('ffmpeg -i %s -ss %s -vf "%s" -vframes 1 %s', videoPath, time, scaleFilter, outputPath)

    -- Execute the command
    local handle = io.popen(cmd)
    local result = handle:read("*a")
    handle:close()

    -- Return the result
    return result
end


-- Function to check if a string starts with another string
function starts_with(str, start)
    return str:sub(1, #start) == start
end

local function getFileSize(filename)
    local file = io.open(filename, "rb")
    if not file then return 0 end
    local size = file:seek("end")
    file:close()
    return size
end

local f = io.open(dest_fname, "r")
if f ~= nil then
    io.close(f)
else
    if starts_with(file_mime, "image/") then
        -- Nếu là file ảnh, kiểm tra nếu là file GIF
        local magick = require("magick")
        if starts_with(file_mime, "image/gif") then
            if getFileSize(source_fname) < 4 * 1024 * 1024 then
                -- Trả lại ảnh gốc
                local source_file = io.open(source_fname, "rb")
                local dest_file = io.open(dest_fname, "wb")
                dest_file:write(source_file:read("*a"))
                source_file:close()
                dest_file:close()
            else
                -- Thực hiện tạo thumbnail
                magick.thumb(source_fname, size, dest_fname, { format = "gif" })
            end
        else
            -- Nếu không phải file GIF, thực hiện resize ảnh
            magick.thumb(source_fname, size, dest_fname)
        end
    elseif starts_with(file_mime, "video/") then
        createThumbnailWithFFmpeg(source_fname, dest_fname, "00:00:05", size)
    else
        ngx.status = 404
    end
end

ngx.header["x-file-name"] = thumbnail_file_name
ngx.header["content-type"] = "image/png"

if file_resp.privacy == 'PUBLIC' then
    ngx.header["cache-control"] = "public, immutable"
else
    ngx.header["cache-control"] = "private, immutable"
end

ngx.req.set_uri("/" .. file_path .. thumbnail_file_name)
ngx.exec("@getthumbnail")
