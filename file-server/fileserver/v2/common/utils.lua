local utils = {}

local lfs = require("lfs")
local ipairs = ipairs
local cjson = require "cjson"

function utils.init_headers()
    ngx.header["Access-Control-Allow-Origin"] = "*"
    ngx.header["Access-Control-Allow-Methods"] = "GET,POST,OPTIONS"
    ngx.header["Access-Control-Allow-Headers"] = "DNT,User-Agent,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Range,Authorization,orgId,Pragma,Connection,Accept,Origin,Referer,Accept-Language,Accept-Encoding,responseType"
    ngx.header["Access-Control-Expose-Headers"] = "Content-Length,Content-Range"
end

function utils.starts_with(str, start)
    if (type(str) ~= 'string' or str == nil or str == '') then
        return false
    end
    return str:sub(1, #start) == start
end

function utils.split(s, delimiter)
    result = {};
    for match in (s .. delimiter):gmatch("(.-)" .. delimiter) do
        table.insert(result, match);
    end
    return result;
end

function utils.return_not_found(msg)
    ngx.status = ngx.HTTP_NOT_FOUND
    ngx.header["Content-type"] = "text/html"
    ngx.say(msg or "not found")
    ngx.exit(0)
end

function isDir(name)
    if type(name) ~= "string" then
        return false
    end
    local cd = lfs.currentdir()
    local is = lfs.chdir(name) and true or false
    lfs.chdir(cd)
    return is
end

function utils.delete_files(dir, name)
    if isDir(dir) then
        for file in lfs.dir(dir) do
            local file_path = dir .. "/" .. file
            if file ~= "." and file ~= ".." then
                if lfs.attributes(file_path, "mode") == "file" then
                    if utils.starts_with(file, name) then
                        os.remove(file_path)
                    end
                end
            end
        end
    end
end

function utils.delete_folder(dir)
    os.execute('rm -rf ' .. dir)
end

function utils.forward_error(status, error_msg)
    ngx.status = status
    ngx.header['content-type'] = 'application/json'
    ngx.say(error_msg)
    ngx.exit(0)
end

function utils.response_error(status, error_msg)
    ngx.status = status
    ngx.header['content-type'] = 'application/json'
    ngx.say('{"error": "' .. tostring(error_msg) .. '", "data": null}')
    ngx.exit(0)
end

return utils
