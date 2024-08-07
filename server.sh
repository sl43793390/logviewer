#!/bin/sh
#输入jar包名称即可
APP_NAME=$2

echo "$2"
usage() {
    echo "Usage: sh scriptName.sh [start|stop|restart|status]"
    exit 1
}

#检查程序是否在运行
is_exist(){
  PID=$(ps -C java -f --width 1000 | grep "$APP_NAME" | grep -v grep | awk '{print $2}')
  #如果不存在返回1，存在返回0
  if [ -z "${PID}" ]; then
   return 1
  else
    return 0
  fi
}

#启动方法
start(){
  is_exist
  if [ $? -eq "0" ]; then
    echo "${APP_NAME} is already running. pid=${PID} ."
  else
  	nohup java -jar -Xmx1024m -Xms512m ${APP_NAME} 2>&1 >app.log &
    echo "${APP_NAME} start success"
  fi
}

#停止方法
stop(){
  is_exist
  if [ $? -eq "0" ]; then
    kill -9 $PID
	echo "${APP_NAME} was successfully stopped"
  else
    echo "${APP_NAME} is not running"
  fi
}

#输出运行状态
status(){
  is_exist
  if [ $? -eq "0" ]; then
    echo "${APP_NAME} is running. Pid is ${pid}"
  else
    echo "${APP_NAME} is NOT running."
  fi
}

#重启
restart(){
  stop
  sleep 3
  start
}

#根据输入参数，选择执行对应方法，不输入则执行使用说明
case "$1" in
  "start")
    start
    ;;
  "stop")
    stop
    ;;
  "status")
    status
    ;;
  "restart")
    restart
    ;;
  *)
    usage
    ;;
esac
