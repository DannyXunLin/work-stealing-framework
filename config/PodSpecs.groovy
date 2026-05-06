def getResources() {
    return [requests: [cpu: "500m", memory: "512Mi"], limits: [cpu: "1", memory: "1Gi"]]
}
def getJvmOpts() {
    return "-Xmx512m -XX:MaxPermSize=256m"
}
return this
