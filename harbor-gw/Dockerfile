FROM eclipse-temurin:17-jre

COPY ./dist/harbor-gw /harbor-gw

WORKDIR /harbor-gw

RUN chmod +x *.sh
# Exec-form (mang JSON): chay thang, khong qua shell trung gian -- dam bao java (duoc "./start-harbor-gw.sh"
# tu exec vao chinh no) la PID 1 thuc su, nhan dung SIGTERM tu k8s de graceful shutdown chay duoc.
CMD ["./start-harbor-gw.sh", "prod"]

EXPOSE 8087 8888
