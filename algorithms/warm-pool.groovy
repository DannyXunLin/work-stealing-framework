import java.util.concurrent.ConcurrentLinkedQueue

def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    def algorithmName = 'warm-pool'
    def frameworkPath = env.WORKSPACE

    def podSpecs = load "${frameworkPath}/config/PodSpecs.groovy"
    def res = podSpecs.getResources()
    def jvmOpts = podSpecs.getJvmOpts()

    def rawTasks = readFile(taskFile).split('\n').findAll { it.trim() }
    def microBatches = []

    rawTasks.each { task ->
        def parts = task.split(':', 3)
        if (parts.size() >= 3 && parts[2].trim()) {
            microBatches.add([bug: parts[0], id: parts[1], classes: parts[2]])
        }
    }

    def CHUNK_SIZE = 3
    def groupedByBug = microBatches.groupBy { it.bug }
    def taskChunks = []

    groupedByBug.each { bug, batches ->
        def currentChunk = []
        batches.each { batch ->
            currentChunk.add(batch)
            if (currentChunk.size() >= CHUNK_SIZE) {
                taskChunks.add([bug: bug, tasks: currentChunk.clone()])
                currentChunk = []
            }
        }
        if (currentChunk.size() > 0) taskChunks.add([bug: bug, tasks: currentChunk])
    }

    Collections.shuffle(taskChunks)
    ConcurrentLinkedQueue globalQueue = new ConcurrentLinkedQueue(taskChunks)

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def currentWorkerId = i + 1
        workerTasks["worker-${currentWorkerId}"] = {

            def podLabel = "wp-${BUILD_ID}-${currentWorkerId}"

            podTemplate(label: podLabel, yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: defects4j
    image: 192.168.56.10:5000/defects4j-lang-bug1:latest
    imagePullPolicy: Always
    tty: true
    command: [cat]
    resources:
      requests: { cpu: "${res.requests.cpu}", memory: "${res.requests.memory}" }
      limits: { cpu: "${res.limits.cpu}", memory: "${res.limits.memory}" }
""") {
                node(podLabel) {
                    container('defects4j') {
                        while (!globalQueue.isEmpty()) {
                            def chunk = globalQueue.poll()
                            if (chunk == null) break

                            def tasksInChunk = chunk.tasks
                            def chunkResultFile = "chunk_result_${BUILD_ID}_${currentWorkerId}_${System.currentTimeMillis()}.txt"
                            def shellScript = """
cd /workspace
export ANT_OPTS='${jvmOpts}'
"""
                            tasksInChunk.each { task ->
                                shellScript += """
start=\$(date +%s%3N)
ant -Dtest.entry=${task.classes} test >/dev/null 2>&1 || true
end=\$(date +%s%3N)
duration=\$(echo "scale=3; (\$end - \$start) / 1000" | bc)
echo "${task.bug}:${task.id},\${duration},${algorithmName}" >> ${chunkResultFile}
"""
                            }
                            timeout(time: 60, unit: 'MINUTES') {
                                sh shellScript
                            }
                            tasksInChunk.each { task ->
                                def resultFile = "result_${task.bug}_${task.id}_${BUILD_ID}.txt"
                                sh "grep '^${task.bug}:${task.id},' ${chunkResultFile} | tail -1 > ${resultFile}"
                                stash name: "res-${task.bug}-${task.id}-${BUILD_ID}", includes: "${resultFile}"
                            }
                            sh "rm -f ${chunkResultFile}"
                        }
                    }
                }
            }
        }
    }
    parallel workerTasks

    node('built-in') {
        def finalLog = "${frameworkPath}/experiments/${algorithmName}/batch_durations_${BUILD_ID}.log"
        sh "touch ${finalLog}"
        microBatches.each { task ->
            try {
                def resultFile = "result_${task.bug}_${task.id}_${BUILD_ID}.txt"
                unstash "res-${task.bug}-${task.id}-${BUILD_ID}"
                sh "cat ${resultFile} >> ${finalLog}"
                sh "rm ${resultFile}"
            } catch (Exception e) {
                echo "WARNING: Failed to collect result for ${task.bug}:${task.id} - ${e.message}"
                sh "echo '${task.bug}:${task.id},-1,${algorithmName}' >> ${finalLog}"
            }
        }
    }
}
return this
