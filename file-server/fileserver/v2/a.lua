local magick = require("magick")

local source_fname = "input.gif"
local dest_fname = "thumbnail.gif"
local thumbnail_width = 100
local thumbnail_height = 100

-- Tải ảnh GIF
local gif = magick.load_image(source_fname)

if gif then
    -- Lấy số lượng khung hình của ảnh GIF
    local num_frames = gif:get("number-scenes")

    if num_frames then
        -- Thực hiện chỉnh sửa kích thước cho từng khung hình của GIF
        for i = 1, num_frames do
            -- Lấy khung hình thứ i
            local frame = gif:get(i)

            if frame then
                -- Thay đổi kích thước của khung hình
                frame:resize(thumbnail_width, thumbnail_height)

                -- Gán khung hình đã chỉnh sửa lại vào ảnh GIF
                gif:set(i, frame)
            end
        end

        -- Thiết lập định dạng của ảnh thành GIF
        gif:set_format("gif")

        -- Lưu lại ảnh thumbnail
        gif:write(dest_fname)

        -- Giải phóng tài nguyên
        gif:destroy()
    else
        -- Xử lý khi không có số lượng khung hình
        print("Không thể lấy số lượng khung hình của ảnh GIF.")
    end
else
    -- Xử lý khi không tải được ảnh GIF
    print("Không thể tải ảnh GIF.")
end
