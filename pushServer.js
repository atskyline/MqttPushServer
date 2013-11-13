var mqtt = require('mqtt');
var util = require("util");
var events = require("events");

Array.prototype.contains = function(element) {  
    for (var i = 0; i < this.length; i++) {  
        if (this[i] == element) {  
            return true;  
        }  
    }  
    return false;  
}

//可以考虑将allUser和offlineMessage持久化
var allUser = [];//只有ClientId
var offlineMessage = [];
var onlineUser = {};

var event = new events.EventEmitter();
exports.event = event;
exports.start = start;
exports.allUser = allUser;
exports.offlineMessage = offlineMessage;
exports.onlineUser = onlineUser;

function start(port){
  if (!port) {port = 1883};

  mqtt.createServer(function(client) {
    client.on('connect', function(packet) {
      registerUser(client,packet);
      client.connack({returnCode: 0});
      pushOfflineMessage(client);
      event.emit('connect',client,packet);
    });

    client.on('publish', function(packet) {
      onPublish(packet);
      event.emit('publish',client,packet);
    });

    client.on('subscribe', function(packet) {
      var granted = [];
      for (var i = 0; i < packet.subscriptions.length; i++) {
        granted.push(packet.subscriptions[i].qos);
      }
      client.suback({granted: granted, messageId: packet.messageId});
      event.emit('subscribe',client,packet);
    });

    client.on('pingreq', function(packet) {
      client.pingresp();
      client.lastPing = new Date().getTime()/1000;
      event.emit('pingreq',client,packet);
    });

    client.on('disconnect', function(packet) {
      client.stream.end();
      event.emit('disconnect',client,packet);
    });

    client.on('close', function(err) {
      delete onlineUser[client.id];
      event.emit('close',client,err);
    });

    client.on('error', function(err) {
      client.stream.end();
      event.emit('error',client,err);
    });
  }).listen(port);
  //开始轮询，定时清理离线用户
  setTimeout(cleanOfflineUser,1000);
}

//向client推送离线消息
function pushOfflineMessage(client){ 
  for (var i = 0; i < offlineMessage.length; i++) {
    if (offlineMessage[i].topic == client.id){
      client.publish({topic: offlineMessage[i].topic, payload: offlineMessage[i].payload});
      offlineMessage.splice(i, 1);
    }
  }
}

//在客户端连接时记录用户信息
function registerUser(client,packet){
    client.id = packet.clientId;
    client.keepAlive = packet.keepalive;
    client.lastPing = new Date().getTime()/1000;
    //mqttjs_开头的客户端是保留给Web端的使用的不需要注册到allUser和onlineUser
    if(client.id.match(/^mqttjs_/i)){
      return;
    }
    if (!allUser.contains(client.id)) {
      allUser.push(client.id);
    }
    onlineUser[client.id] = client;
}

function onPublish(packet){
    var target = [];
    var info = packet.topic.split("/");
    //info[0]为AppName 
    //info[1]为终端标识或者通配符#

    //收集需要推送的目标
    if (info[1] != "*") {
      target.push(packet.topic);
    }else{
      var reg = new RegExp("^"+info[0]+"/", "i");
      for(var i = 0; i < allUser.length; i++){
        if (allUser[i].match(reg)) {
          target.push(allUser[i]);
        }
      }
    }

    //对在线用户进行推送
    for (var c in onlineUser) {
      for (var i = 0; i < target.length; i++) {
        if (target[i] == onlineUser[c].id){
          onlineUser[c].publish({topic: target[i], payload: packet.payload});
          target.splice(i, 1);
          break;
        }
      }
    }

    //把非在线用户的消息保存到offlineMessage,等用户连接的时候进行推送
    for (var i = 0; i < target.length; i++) {
      offlineMessage.push({topic: target[i], payload: packet.payload});
    }
}

//定时清理离线用户,清理上次ping时间与当前时间差大于keepAlive1.5倍的客户端
function cleanOfflineUser (){
  var now = new Date().getTime()/1000;
  for (var c in onlineUser) {
    if (now - onlineUser[c].lastPing > onlineUser[c].keepAlive * 1.5) {
      event.emit('clean',onlineUser[c]);
      delete onlineUser[c];
    }
  }
  setTimeout(cleanOfflineUser,1000);
}

