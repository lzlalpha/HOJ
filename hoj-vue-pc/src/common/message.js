import Vue from 'vue'


const message=function(type,msg,duration=4000){
    return Vue.prototype.$msg({ type: type, message: msg, zIndex: 3000 ,position:'top-center',duration:duration})
}


const error = function (msg) {
    return message('error',msg)
}

const success = function (msg) {
    return message('success',msg)
}

const info = function (msg) {
    return message('info',msg)
}

const waring = function (msg) {
    return message('waring',msg)
}
const loading = function(msg){
    return message('loading',msg)
}

const mMessage = {
    error,
    success,
    info,
    waring,
    loading,
    message
}

export default mMessage;