image=legofile:1.1.1
docker login --username nnv98 --password namtk9142857
docker build --tag=$image .
docker tag $image nnv98/$image
docker push nnv98/$image
sh helm-stop.sh
sh helm-run.sh