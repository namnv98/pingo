FROM eclipse-temurin:17-jre

COPY ./dist/hall /hall

WORKDIR /hall

RUN chmod +x *.sh
# Exec-form (mang JSON): chay thang, khong qua shell trung gian -- dam bao java (duoc "./start-hall.sh"
# tu exec vao chinh no) la PID 1 thuc su, nhan dung SIGTERM tu k8s de graceful shutdown chay duoc.
CMD ["./start-hall.sh", "prod"]

EXPOSE 8085
