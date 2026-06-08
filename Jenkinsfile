pipeline {
    agent any
    parameters {
        choice(
            name: 'ALGORITHM',
            choices: ['round-robin', 'random-dynamic', 'lpt-dynamic', 'spt-dynamic', 'ALL'],
            description: '選擇演算法'
        )
        string(name: 'WORKER_COUNT', defaultValue: '1', description: 'Worker 數量')
        string(
            name: 'TASK_FILE',
            defaultValue: '/var/lib/jenkins/batch_tasks_bug1_real_10x.txt',
            description: '任務列表檔案路徑'
        )
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

                    // 判斷要跑哪些演算法
                    def algosToRun = []
                    if (params.ALGORITHM == 'ALL') {
                        // ema-dynamic 放最後，讓它可以吃前面的歷史紀錄
                        algosToRun = ['round-robin', 'random-dynamic', 'lpt-dynamic', 'spt-dynamic']
                    } else {
                        algosToRun = [params.ALGORITHM]
                    }

                    // 依序執行每個演算法
                    algosToRun.each { algoName ->
                        echo "======================================================="
                        echo "🚀 準備執行演算法: ${algoName} (Worker 數量: ${params.WORKER_COUNT})"
                        echo "======================================================="

                        sh "mkdir -p ${env.FRAMEWORK_PATH}/experiments/${algoName}"
                        if (params.CLEAN_REPORTS) {
                            sh "rm -rf ${env.FRAMEWORK_PATH}/experiments/${algoName}/* || true"
                        }

                        def algorithmFile = "${env.FRAMEWORK_PATH}/algorithms/${algoName}.groovy"
                        def algorithm = load algorithmFile

                        echo "⏱️ [System] 開始測量真實執行時間 (Wall Time)..."
                        long startTime = System.currentTimeMillis()

                        algorithm.execute(
                            taskFile: params.TASK_FILE,
                            workerCount: params.WORKER_COUNT.toInteger()
                        )

                        long endTime = System.currentTimeMillis()
                        def duration = (endTime - startTime) / 1000.0
                        echo "⏱️ [System] ${algoName} 執行結束！總耗時: ${duration} 秒"

                        def runtimeFile = "${env.FRAMEWORK_PATH}/experiments/${algoName}/runtime_${params.WORKER_COUNT}w.txt"
                        sh "echo '${duration}' > ${runtimeFile}"

                        // 確認結果
                        def logFile = "${env.FRAMEWORK_PATH}/experiments/${algoName}/batch_durations_${BUILD_ID}.log"
                        if (fileExists(logFile)) {
                            def count = sh(script: "wc -l < ${logFile}", returnStdout: true).trim()
                            echo "✅ ${algoName} 實驗完成！成功處理批次數：${count}"
                        }
                    }
                }
            }
        }
        stage('生成報告與存檔') {
            steps {
                script {
                    archiveArtifacts artifacts: "experiments/**/*.tar.gz, experiments/**/runtime_*.txt", allowEmptyArchive: true
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
