FROM eclipse-temurin:17-jre

COPY ./dist/colony /colony

WORKDIR /colony

RUN chmod +x *.sh
# Exec-form (mang JSON): chay thang, khong qua shell trung gian -- dam bao java (duoc "./start-colony.sh"
# tu exec vao chinh no) la PID 1 thuc su, nhan dung SIGTERM tu k8s de graceful shutdown chay duoc.
CMD ["./start-colony.sh", "prod"]

EXPOSE 8086 9999
