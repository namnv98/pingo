FROM eclipse-temurin:17-jre

COPY ./dist/harbor /harbor

WORKDIR /harbor

RUN chmod +x *.sh
# Exec-form (mang JSON): chay thang, khong qua shell trung gian -- dam bao java (duoc "./start-harbor.sh"
# tu exec vao chinh no) la PID 1 thuc su, nhan dung SIGTERM tu k8s de graceful shutdown chay duoc.
CMD ["./start-harbor.sh", "prod"]

EXPOSE 8087 8888
