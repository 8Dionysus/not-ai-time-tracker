/* SPDX-License-Identifier: Apache-2.0
 * Descriptor-relative private-copy operations. No traversal or recursive deletion API.
 * All descriptors stay anchored to a no-follow root; callers hold the common writer gate. */
#define _GNU_SOURCE
#include <jni.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define JNI(name) Java_dev_notai_tracker_NativeCopyIo_##name
static void error(JNIEnv *e,const char *op) {
    char message[256];snprintf(message,sizeof message,"%s: %s",op,strerror(errno));
    (*e)->ThrowNew(e,(*e)->FindClass(e,"java/io/IOException"),message);
}
static int safe(const char *s) {
    if(!*s || !strcmp(s,".") || !strcmp(s,"..")) return 0;
    for(;*s;s++) if(!((*s>='a'&&*s<='z')||(*s>='A'&&*s<='Z')||(*s>='0'&&*s<='9')||*s=='.'||*s=='_'||*s=='-')) return 0;
    return 1;
}
/* Return the pinned parent and one validated leaf, never follow any ancestor symlink. */
static int parent(JNIEnv *e,int root,const char *path,char **leaf) {
    if(!*path || *path=='/' || path[strlen(path)-1]=='/' || strstr(path,"//")) { errno=EINVAL;error(e,"relative path");return -1; }
    char *copy=strdup(path),*save=NULL,*part=strtok_r(copy,"/",&save);int fd=dup(root);
    while(part && fd>=0) {
        if(!safe(part)) { errno=EINVAL;break; }
        char *next=strtok_r(NULL,"/",&save);
        if(!next) { *leaf=strdup(part);free(copy);return fd; }
        int child=openat(fd,part,O_RDONLY|O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW);close(fd);fd=child;part=next;
    }
    if(fd>=0) close(fd);free(copy);error(e,"open parent");return -1;
}
static int directory(JNIEnv *e,int root,const char *path) {
    if(!*path) return dup(root);
    char *leaf=NULL;int p=parent(e,root,path,&leaf);if(p<0) return -1;
    int fd=openat(p,leaf,O_RDONLY|O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW);int saved=errno;free(leaf);close(p);errno=saved;
    if(fd<0) error(e,"open directory");return fd;
}
static jlongArray attributes(JNIEnv *e,const struct stat *s) {
    jlong values[]={s->st_dev,s->st_ino,s->st_nlink,s->st_mode,s->st_size,(jlong)s->st_mtim.tv_sec*1000000000+s->st_mtim.tv_nsec};
    jlongArray a=(*e)->NewLongArray(e,6);if(a) (*e)->SetLongArrayRegion(e,a,0,6,values);return a;
}
JNIEXPORT jlong JNICALL JNI(root)(JNIEnv *e,jobject self,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return -1;
    int root=open("/",O_RDONLY|O_CLOEXEC|O_DIRECTORY);int fd=-1;
    if(*s=='/' && s[1]) fd=directory(e,root,s+1);else { errno=EINVAL;error(e,"absolute root"); }
    close(root);(*e)->ReleaseStringUTFChars(e,path,s);return fd;
}
JNIEXPORT void JNICALL JNI(close)(JNIEnv *e,jobject self,jlong fd) { (void)self;if(close((int)fd)<0) error(e,"close"); }
JNIEXPORT jlongArray JNICALL JNI(fstat)(JNIEnv *e,jobject self,jlong fd) {
    (void)self;struct stat s;if(fstat((int)fd,&s)<0) { error(e,"fstat");return NULL; }return attributes(e,&s);
}
JNIEXPORT jlongArray JNICALL JNI(stat)(JNIEnv *e,jobject self,jlong root,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return NULL;
    char *leaf=NULL;int p=parent(e,(int)root,s,&leaf);struct stat st;int result=-1,saved=0;
    if(p>=0) { result=fstatat(p,leaf,&st,AT_SYMLINK_NOFOLLOW);saved=errno;free(leaf);close(p); }
    (*e)->ReleaseStringUTFChars(e,path,s);
    if(p<0) { if(errno==ENOENT) (*e)->ExceptionClear(e);return NULL; }
    if(result<0) { if(saved!=ENOENT) { errno=saved;error(e,"stat"); }return NULL; }return attributes(e,&st);
}
JNIEXPORT jobjectArray JNICALL JNI(names)(JNIEnv *e,jobject self,jlong root,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return NULL;
    int fd=directory(e,(int)root,s);(*e)->ReleaseStringUTFChars(e,path,s);if(fd<0) return NULL;
    DIR *d=fdopendir(fd);if(!d) { close(fd);error(e,"fdopendir");return NULL; }
    char **names=NULL;size_t n=0;struct dirent *entry;int bad=0;errno=0;
    while((entry=readdir(d))) {
        if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,"..")) continue;
        if(!safe(entry->d_name) || n>=100000) { errno=EINVAL;bad=1;break; }
        char **next=realloc(names,(n+1)*sizeof *names);if(!next) { errno=ENOMEM;bad=1;break; }names=next;
        names[n]=strdup(entry->d_name);if(!names[n]) { bad=1;break; }n++;
    }
    if(errno) bad=1;int saved=errno;closedir(d);
    jobjectArray a=NULL;
    if(!bad) { a=(*e)->NewObjectArray(e,(jsize)n,(*e)->FindClass(e,"java/lang/String"),NULL);
        if(a) for(size_t i=0;i<n;i++) { jstring name=(*e)->NewStringUTF(e,names[i]);(*e)->SetObjectArrayElement(e,a,(jsize)i,name);(*e)->DeleteLocalRef(e,name); } }
    for(size_t i=0;i<n;i++) free(names[i]);free(names);
    if(bad) { errno=saved;error(e,"readdir"); }return a;
}
JNIEXPORT void JNICALL JNI(mkdir)(JNIEnv *e,jobject self,jlong root,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return;
    char *leaf=NULL;int p=parent(e,(int)root,s,&leaf);
    if(p>=0) { if(mkdirat(p,leaf,0700)<0 && errno!=EEXIST) error(e,"mkdirat");
        if(!(*e)->ExceptionCheck(e)) { int fd=openat(p,leaf,O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);if(fd<0) error(e,"mkdir verify");else { if(fsync(fd)<0||fsync(p)<0) error(e,"mkdir fsync");close(fd); } }
        free(leaf);close(p); }
    (*e)->ReleaseStringUTFChars(e,path,s);
}
JNIEXPORT jlong JNICALL JNI(open)(JNIEnv *e,jobject self,jlong root,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return -1;
    char *leaf=NULL;int p=parent(e,(int)root,s,&leaf);int fd=-1;
    if(p>=0) { fd=openat(p,leaf,O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);struct stat st;
        if(fd>=0 && (fstat(fd,&st)<0 || !S_ISREG(st.st_mode) || st.st_nlink!=1)) { close(fd);fd=-1;errno=EINVAL; }
        if(fd<0) error(e,"open regular single-link file");free(leaf);close(p); }
    (*e)->ReleaseStringUTFChars(e,path,s);return fd;
}
JNIEXPORT jbyteArray JNICALL JNI(chunk)(JNIEnv *e,jobject self,jlong fd) {
    (void)self;jbyte buffer[65536];ssize_t n;do { n=read((int)fd,buffer,sizeof buffer); }while(n<0 && errno==EINTR);
    if(n<0) { error(e,"read");return NULL; }jbyteArray a=(*e)->NewByteArray(e,(jsize)n);if(a) (*e)->SetByteArrayRegion(e,a,0,(jsize)n,buffer);return a;
}
JNIEXPORT void JNICALL JNI(put)(JNIEnv *e,jobject self,jlong root,jstring path,jbyteArray bytes) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return;
    char *leaf=NULL;int p=parent(e,(int)root,s,&leaf);
    if(p>=0) { int fd=openat(p,leaf,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd<0) error(e,"create metadata");else { jsize length=(*e)->GetArrayLength(e,bytes);jbyte *data=(*e)->GetByteArrayElements(e,bytes,NULL);
            if(data) { ssize_t offset=0;while(offset<length) { ssize_t n=write(fd,data+offset,(size_t)(length-offset));if(n<0&&errno==EINTR) continue;if(n<=0) { error(e,"write");break; }offset+=n; }
                if(!(*e)->ExceptionCheck(e) && fsync(fd)<0) error(e,"file fsync");(*e)->ReleaseByteArrayElements(e,bytes,data,JNI_ABORT); }close(fd); }
        free(leaf);close(p); }
    (*e)->ReleaseStringUTFChars(e,path,s);
}
JNIEXPORT void JNICALL JNI(move)(JNIEnv *e,jobject self,jlong root,jstring source,jstring target,jboolean replace) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,source,NULL),*t=(*e)->GetStringUTFChars(e,target,NULL);if(!s||!t) return;
    char *sl=NULL,*tl=NULL;int sp=parent(e,(int)root,s,&sl),tp=-1;if(sp>=0) tp=parent(e,(int)root,t,&tl);
    if(sp>=0 && tp>=0) { int result;
        if(replace) result=renameat(sp,sl,tp,tl);
        else {
#ifdef SYS_renameat2
            result=(int)syscall(SYS_renameat2,sp,sl,tp,tl,1 /* RENAME_NOREPLACE */);
#else
            errno=ENOSYS;result=-1;
#endif
        }
        if(result<0) error(e,"renameat no cross-filesystem fallback");else if(fsync(sp)<0||fsync(tp)<0) error(e,"rename parent fsync"); }
    if(sp>=0) close(sp);if(tp>=0) close(tp);free(sl);free(tl);
    (*e)->ReleaseStringUTFChars(e,source,s);(*e)->ReleaseStringUTFChars(e,target,t);
}
JNIEXPORT void JNICALL JNI(remove)(JNIEnv *e,jobject self,jlong root,jstring path,jboolean directoryFlag,jlong device,jlong inode) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return;
    char *leaf=NULL;int p=parent(e,(int)root,s,&leaf);
    if(p>=0) { struct stat st;
        if(fstatat(p,leaf,&st,AT_SYMLINK_NOFOLLOW)<0) error(e,"unlink stat");
        else if((jlong)st.st_dev!=device || (jlong)st.st_ino!=inode || (directoryFlag && !S_ISDIR(st.st_mode)) || (!directoryFlag && (!S_ISREG(st.st_mode)||st.st_nlink!=1))) { errno=EINVAL;error(e,"unlink identity/kind/link"); }
        else if(unlinkat(p,leaf,directoryFlag?AT_REMOVEDIR:0)<0) error(e,"unlinkat");else if(fsync(p)<0) error(e,"unlink parent fsync");
        free(leaf);close(p); }
    (*e)->ReleaseStringUTFChars(e,path,s);
}
JNIEXPORT void JNICALL JNI(sync)(JNIEnv *e,jobject self,jlong root,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return;
    int fd=directory(e,(int)root,s);if(fd>=0) { if(fsync(fd)<0) error(e,"directory fsync");close(fd); }(*e)->ReleaseStringUTFChars(e,path,s);
}
