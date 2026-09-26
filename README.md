# Payment Service

Payment Service is a Spring Boot microservice responsible for managing payments associated with restaurant orders.

## Configuration

The application uses environment variables for database configuration.

### Environment Variables

Create a local `.env` file in the project root, according to `.env.example`, provided as a template.

The host port used by the **Payment Service** also configured in the .env file, under the name `SERVER_HOST_PORT`.
This port is used on the host machine and can be changed if it conflicts with ports used by other local services.

## Running with Docker Compose

Make sure Docker Desktop is running.

### 1. Start the application and PostgreSQL

```bash
docker compose up -d --build
```

### 2. Check container status

```bash
docker compose ps
```

The following services should be running:

| Service           | Description             |
| ----------------- | ----------------------- |
| `payment-service` | Spring Boot application |
| `payment-db`      | PostgreSQL database     |

The `payment-db` container should eventually show a **healthy** status.

### 3. View application logs (if needed)

```bash
docker compose logs -f payment-service
```

## Running with Kubernetes

### 1. Configure `payment-secret.yaml`

Configure `k8s/payment-secret.yaml` using `k8s/payment-secret.yaml.example`.


### 2. Build the Docker image

Build the `payment-service` image:

```shell
docker build -t payment-service:latest .
```

Docker Desktop Kubernetes can use the locally built image, so no image loading step is required.

### 3. Run `dry-run`

Validate the Kubernetes manifests on the client side:

```shell
kubectl apply -f k8s/. --dry-run=client
```

Validate the manifests against the Kubernetes API server:

```shell
kubectl apply -f k8s/. --dry-run=server
```

### 4. Apply the Kubernetes manifests

Deploy the database, service, ConfigMap, Secret, and other Kubernetes resources:

```shell
kubectl apply -f k8s/.
```

### 5. Check the pods

Check the status of the deployed pods:

```shell
kubectl get pods
```

The expected result is two `payment-service` replicas and one `payment-db` pod in the `Running` state.

### 6. Check the deployment

```shell
kubectl get deployment payment-service
```

The `READY` value should be `2/2`.

### 7. Port-forward the service

To access the `payment-service` from the host machine:

```shell
kubectl port-forward svc/payment-service 8084:8080
```

The service is then available at:

```text
http://localhost:8084
```

For example, the health endpoint can be checked at:

```text
http://localhost:8084/actuator/health
```


## API

The Payment Service is available at:

```text
http://localhost:<chosem port>
```

### Create a Payment

**POST** `/payment`

Test request:

```http
POST http://localhost:8084/payment
Content-Type: application/json
```

```json
{
  "orderId": 1
}
```

Expected response:

```json
{
  "id": 1,
  "orderId": 1,
  "status": "PENDING",
  "createdAt": "2026-09-19T17:00:00"
}
```

### Get All Payments

**GET** `/payment`

```text
GET http://localhost:8084/payment
```

### Get Payment by ID

**GET** `/payment/{id}`

```text
GET http://localhost:8084/payment/1
```

### Get Non-Existing Payment

**GET** `/payment/{id}`

```text
GET http://localhost:8084/payment/999
```

If the payment does not exist, the service returns:

```text
Payment with id 999 not found
```

with HTTP status:

```text
404 Not Found
```

### Update a Payment

**PUT** `/payment/{id}`

```http
PUT http://localhost:8084/payment/1
Content-Type: application/json
```

```json
{
  "orderId": 2
}
```

### Delete a Payment

**DELETE** `/payment/{id}`

```text
DELETE http://localhost:8084/payment/1
```

## Database

The Payment Service connects to PostgreSQL using the Docker service name:

```text
payment-db:5432
```

PostgreSQL data is stored in the Docker volume:

```text
payment-db-data
```

The application and PostgreSQL run on the Docker network:

```text
payment-network
```

## Testing Database Persistence

The PostgreSQL data should survive container restarts.

### 1. Create a payment

Send a `POST /payment` request:

```http
POST http://localhost:8084/payment
```

```json
{
  "orderId": 1
}
```

Note the returned payment ID.

### 2. Verify the payment exists

Send:

```text
GET http://localhost:8084/payment
```

Make sure the newly created payment is present.

### 3. Stop the containers

```bash
docker compose down
```

> This stops and removes the containers but keeps the PostgreSQL volume.

### 4. Start the containers again

```bash
docker compose up -d
```

Check the container status:

```bash
docker compose ps
```

The `payment-db` container should eventually show a **healthy** status.

### 5. Verify the data

Send:

```text
GET http://localhost:8084/payment
```

The payment created before the restart should still be present.

This confirms that PostgreSQL data is persisted in the Docker volume.

## Stopping the Application

### Stop containers and keep database data

```bash
docker compose down
```

Start them again with:

```bash
docker compose up -d
```

### Remove containers and the PostgreSQL volume

```bash
docker compose down -v
```

> ⚠️ **Warning:** `docker compose down -v` permanently removes the local PostgreSQL data stored in the Docker volume.
