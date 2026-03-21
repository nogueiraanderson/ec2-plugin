# EC2 Plugin for Jenkins - Build, Test & Deploy
# Patched version with NPE guard to prevent ComputerRetentionWork timer death (Percona)

version := "5.24.percona.1"
image := "maven:3.9-eclipse-temurin-17"
container := "ec2-build"
m2_volume := "ec2-m2-cache"
instances := "pmm ps80 psmdb pxb pxc pg ps57 rel cloud ps3"

# Build plugin .hpi (skipping tests for speed)
build:
    #!/usr/bin/env bash
    set -euo pipefail
    docker volume create {{m2_volume}} >/dev/null 2>&1 || true
    docker rm -f {{container}} 2>/dev/null || true
    docker create --name {{container}} -w /plugin \
        -v {{m2_volume}}:/root/.m2/repository \
        {{image}} \
        mvn clean package -DskipTests -Drevision={{version}}
    docker cp "$(pwd)/." {{container}}:/plugin
    docker start -a {{container}}
    docker cp {{container}}:/plugin/target/ec2.hpi ./ec2-{{version}}.hpi
    docker rm {{container}}
    ls -lh ./ec2-{{version}}.hpi

# Build and run tests
test:
    #!/usr/bin/env bash
    set -euo pipefail
    docker volume create {{m2_volume}} >/dev/null 2>&1 || true
    docker rm -f {{container}} 2>/dev/null || true
    docker create --name {{container}} -w /plugin \
        -v {{m2_volume}}:/root/.m2/repository \
        {{image}} \
        mvn clean verify -Drevision={{version}}
    docker cp "$(pwd)/." {{container}}:/plugin
    docker start -a {{container}}
    docker rm {{container}}

# Deploy to a single instance (upload + pin + smart restart)
deploy inst:
    ./scripts/deploy.sh {{inst}} {{version}}

# Deploy to all 10 instances
deploy-all:
    #!/usr/bin/env bash
    set -euo pipefail
    for inst in {{instances}}; do
        ./scripts/deploy.sh "$inst" {{version}}
    done

# Check plugin version across all instances
check:
    ./scripts/check.sh "{{instances}}"

# Create a GitHub release and tag
release:
    #!/usr/bin/env bash
    set -euo pipefail
    tag="v{{version}}"
    hpi="ec2-{{version}}.hpi"
    if [[ ! -f "$hpi" ]]; then
        echo "HPI not found. Run: just build"
        exit 1
    fi
    git tag -s "$tag" -m "Release {{version}}"
    git push origin "$tag"
    gh release create "$tag" "$hpi" \
        --title "{{version}}" \
        --notes "EC2 Plugin {{version}} (Percona patched) - NPE guard in getState/describeInstance"
    echo "Released $tag with $hpi"

# Clean build artifacts and cache
clean:
    rm -f ec2-*.hpi
    docker rm -f {{container}} 2>/dev/null || true

# Nuke Maven cache volume (forces full re-download)
clean-cache:
    docker volume rm {{m2_volume}} 2>/dev/null || true
