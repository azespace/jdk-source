### :smiling_face_with_three_hearts:  JDK源码学习，基于JDK1.8

> 安装JDK源码
- 安装jdk-8u202-windows-x64
- 拉取代码修改idea的project structure 中的几个功能
- sdks新增一个jdk , home path为新安装的jdk sourcepath移除src.zip 并添加为自己项目的路径xxx/jdk-source/src/main/java
- project 的sdk设置为新增的sdks, language level 为 8
- modules 中的language level 为 8 sdk设置为新增的sdks
- libraries 新增工具类 将原生的java安装目录下的lib下的tools.jar添加进去
- idea设置compiler中的java compiler 为 1.8 ,compiler的 head size 为 1000以上 自行调整
- 运行main方法下的main方法不报错即可