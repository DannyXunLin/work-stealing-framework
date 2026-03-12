def getResources() {
    return [requests: [cpu: "1", memory: "2Gi"], limits: [cpu: "2", memory: "4Gi"]]
}
def getJvmOpts() {
    return "-Xmx2g -XX:MaxPermSize=512m"
}
return this
