def getResources() {
    return [requests: [cpu: "500m", memory: "1Gi"], limits: [cpu: "1", memory: "2Gi"]]
}
def getJvmOpts() {
    return "-Xmx1500m -XX:MaxMetaspaceSize=256m"
}
return this
