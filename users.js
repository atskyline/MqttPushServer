var pushServer = require('./pushServer');

function User(appName,id,isOnline){
  this.appName = appName;
  this.id = id;
  this.isOnline = isOnline;
}

function isOnline(user){
  if (pushServer.onlineUser.hasOwnProperty(user)) {
    return true;
  }
  return false;
}

exports.get = function(req,res){
  var result = [];
  for (var i = 0; i < pushServer.allUser.length; i++) {
    var info = pushServer.allUser[i].split("/");
    var appName = info[0];
    var clientName = info[1];

    var appInResult = false;
    for (var j = 0; j < result.length; j++) {
      if (result[j].appName == appName) {
        //app已经在result中
        result[j].clients.push({
          clientName : clientName,
          isOnline:isOnline(pushServer.allUser[i])
        });
        appInResult = true;
        break;
      }
    }
    //app还不在Result中，新建一个app对象，并添加入client
    if (!appInResult) {
      result.push({
        appName : appName,
        clients : []
      });
      result[result.length - 1].clients.push({
        clientName : clientName,
        isOnline:isOnline(pushServer.allUser[i])
      });
    }
  }
  res.send(result);
}
