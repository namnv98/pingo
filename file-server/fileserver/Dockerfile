# Sử dụng hình ảnh OpenResty đã có sẵn làm cơ sở
FROM nnv98/file:1.1.3
# Tùy chỉnh cấu hình OpenResty nếu cần thiết
# COPY nginx.conf /usr/local/openresty/nginx/conf/nginx.conf

# Copy các file cấu hình hoặc mã Lua của bạn vào hình ảnh
# COPY config.lua /path/to/config.lua

# Khởi động OpenResty khi container được chạy
#CMD ["/usr/local/openresty/nginx/sbin/nginx", "-g", "daemon off;"]

COPY ./nginx/conf.d/default.conf /etc/nginx/conf.d/
COPY ./nginx/nginx.conf /usr/local/openresty/nginx/conf/nginx.conf
COPY v2 /opt/v2
COPY start.sh /usr/bin/
RUN chmod a+x /usr/bin/start.sh
COPY nginx/resolvers.conf /usr/local/openresty/nginx/conf/resolvers.conf
EXPOSE 80