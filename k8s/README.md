# k3s 배포 매니페스트

레지스트리 없이(비용 0원 원칙) 노드에서 이미지를 직접 빌드해서 containerd로
가져오는 구조. 아래 순서로 진행한다.

## 1. 이미지 빌드 + k3s로 임포트

인스턴스에 이 레포를 올린 뒤(git clone 또는 rsync), 인스턴스 안에서:

```bash
docker build -t trova-backend:latest .
docker save trova-backend:latest | sudo k3s ctr images import -
```

이미지를 다시 빌드했으면 이 두 명령을 다시 실행한 뒤 `kubectl rollout restart
deployment/trova-backend`로 새 이미지를 반영해야 한다 — `imagePullPolicy:
IfNotPresent`라 태그가 같으면 자동으로 새로 안 받아온다.

## 2. 시크릿 준비

```bash
cp k8s/secret.example.yaml k8s/secret.yaml
# k8s/secret.yaml을 열어서 실제 값 채우기 (커밋 안 됨, .gitignore 등록됨)
kubectl apply -f k8s/secret.yaml
```

## 3. 배포

```bash
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl get pods -w
```

## 4. 확인

```bash
curl http://localhost:30080/api/auth/me   # 401이면 정상(비로그인 상태)
```

## 참고

- `k8s/deployment.yaml`의 리소스 값(request/limit)은 Ampere A1(2 OCPU/12GB)
  기준. 1 OCPU/~500MB AMD Always Free 인스턴스에서는 k3s 부트스트랩 자체가
  두 번 다 멈췄음(실측, PROGRESS.md 2026-08-21 항목 참고) — 최소 A1급 노드를
  전제로 한다.
- Python 파이프라인(`pipeline-test/`)은 이미지 안에 이미 포함돼 있음
  (`Dockerfile` 참고) — 별도 설치 불필요.
