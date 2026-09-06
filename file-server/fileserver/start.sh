echo resolver $(awk 'BEGIN{ORS=" "} $1=="nameserver" {print $2}' /etc/resolv.conf) ";" > /usr/local/openresty/nginx/conf/resolvers.conf



