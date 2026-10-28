package com.hcjeong.forestix.common

/** Only retires a resource after its last inference lease, including errors. */
internal class IdleResourceCache<T:Any>(private val dispose:(T)->Unit) {
    private val guard=Any()
    private var value:T?=null
    private var users=0
    private var trimRequested=false
    fun <R> withResource(create:()->T,operation:(T)->R):R {
        val resource=synchronized(guard){
            val current=value?:create().also{value=it}
            users++;current
        }
        try{return operation(resource)}finally{
            val retired=synchronized(guard){
                users--
                if(users==0&&trimRequested){trimRequested=false;value.also{value=null}}else null
            }
            retired?.let(dispose)
        }
    }
    fun trim(){
        val retired=synchronized(guard){
            if(users==0){trimRequested=false;value.also{value=null}}
            else{trimRequested=true;null}
        }
        retired?.let(dispose)
    }
}
