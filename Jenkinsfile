pipeline {
    agent any
    parameters {
        choice(name: 'ALGORITHM', choices: ['ema-dynamic', 'baseline', 'hybrid', 'a2ws', 'ema', 'rl', 'work-stealing'], description: '選擇演算法')
        string(name: 'WORKER_COUNT', defaultValue: '4', description: 'Worker 數量')
        string(name: 'TASK_FILE', defaultValue: '/var/lib/jenkins/batch_tasks_fine_grained.txt', description: '任務列表檔案路徑')
        booleanParam(name: 'CLEAN_REPORTS', defaultValue: true, description: '執行前清理舊報告')
    }
    options {
        timeout(time: 6, unit: 'HOURS') 
        timestamps()
    }
    stages {
        stage('準備與執行') {
            steps {
                script {
                    env.FRAMEWORK_PATH = env.WORKSPACE 
                    sh "mkdir -p ${env.FRAMEWORK_PATH}/experiments/${params.ALGORITHM}"
                    if (params.CLEAN_REPORTS) {
                        sh "rm -rf ${env.FRAMEWORK_PATH}/experiments/${params.ALGORITHM}/* || true"
                    }
                    def algorithmFile = "${env.FRAMEWORK_PATH}/algorithms/${params.ALGORITHM}.groovy"
                    def algorithm = load algorithmFile
                    
                    echo "⏱️ [System] 開始測量真實執行時間 (Wall Time)..."
                    long startTime = System.currentTimeMillis()
                    algorithm.execute(taskFile: params.TASK_FILE, workerCount: params.WORKER_COUNT.toInteger())
                    long endTime = System.currentTimeMillis()
                    
                    def duration = (endTime - startTime) / 1000.0
                    echo "⏱️ [System] 執行結束！總耗時: ${duration} 秒"
                    def runtimeFile = "${env.FRAMEWORK_PATH}/experiments/${params.ALGORITHM}/runtime_${params.WORKER_COUNT}w.txt"
                    sh "echo '${duration}' > ${runtimeFile}"
                }
            }
        }
        stage('生成報告與存檔') {
            steps {
                script {
                    sh "ln -sfn ${env.FRAMEWORK_PATH}/experiments/${params.ALGORITHM} current_exp"
                    archiveArtifacts artifacts: "current_exp/*.tar.gz, current_exp/runtime_*.txt", allowEmptyArchive: true
                    def logFile = "${env.FRAMEWORK_PATH}/experiments/${params.ALGORITHM}/batch_durations_${BUILD_ID}.log"
                    if (fileExists(logFile)) {
                        def count = sh(script: "wc -l < ${logFile}", returnStdout: true).trim()
                        echo "✅ 實驗完成！成功處理批次數：${count}"
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                sh "rm -f current_exp || true"
                sh "rm -f /tmp/tasks_${BUILD_ID}.txt || true"
                dir("${env.FRAMEWORK_PATH}/experiments") {
                    sh "python3 plot_comparison.py || true" 
                }
            }
        }
    }
}
