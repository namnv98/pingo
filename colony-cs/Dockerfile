FROM eclipse-temurin:17-jre

COPY ./dist/colony-cs /colony-cs

WORKDIR /colony-cs

RUN chmod +x *.sh
# Exec-form (mang JSON): chay thang, khong qua shell trung gian -- dam bao java (duoc "./start-colony-cs.sh"
# tu exec vao chinh no) la PID 1 thuc su, nhan dung SIGTERM tu k8s de graceful shutdown chay duoc.
CMD ["./start-colony-cs.sh", "prod"]

EXPOSE 8085 9999
