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
#include <stdint.h>

#define JNI(name) Java_dev_notai_tracker_NativeCopyIo_##name
static void error(JNIEnv *e,const char *op) {
    int saved=errno;char message[256];snprintf(message,sizeof message,"%s: %s",op,strerror(saved));
    jclass type=(*e)->FindClass(e,"java/io/IOException");
    if(type) { (*e)->ThrowNew(e,type,message);(*e)->DeleteLocalRef(e,type); }
    errno=saved;
}
static int safe(const char *s) {
    if(!*s || !strcmp(s,".") || !strcmp(s,"..")) return 0;
    for(;*s;s++) if(!((*s>='a'&&*s<='z')||(*s>='A'&&*s<='Z')||(*s>='0'&&*s<='9')||*s=='.'||*s=='_'||*s=='-')) return 0;
    return 1;
}
/* Return the pinned parent and one validated leaf, never follow any ancestor symlink. */
static int parent_mode(JNIEnv *e,int root,const char *path,char **leaf,int traversal_flags) {
    if(!*path || *path=='/' || path[strlen(path)-1]=='/' || strstr(path,"//")) { errno=EINVAL;error(e,"relative path");return -1; }
    char *copy=strdup(path);if(!copy) { errno=ENOMEM;error(e,"path allocation");return -1; }
    char *save=NULL,*part=strtok_r(copy,"/",&save);int fd=dup(root);
    while(part && fd>=0) {
        if(!safe(part)) { errno=EINVAL;break; }
        char *next=strtok_r(NULL,"/",&save);
        if(!next) { *leaf=strdup(part);free(copy);if(!*leaf) { close(fd);errno=ENOMEM;error(e,"leaf allocation");return -1; }return fd; }
        int child=openat(fd,part,traversal_flags|O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW);close(fd);fd=child;part=next;
    }
    if(fd>=0) close(fd);free(copy);error(e,"open parent");return -1;
}
static int parent(JNIEnv *e,int root,const char *path,char **leaf) {
    return parent_mode(e,root,path,leaf,O_RDONLY);
}
static int directory(JNIEnv *e,int root,const char *path) {
    if(!*path) { int fd=dup(root);if(fd<0) error(e,"duplicate directory");return fd; }
    char *leaf=NULL;int p=parent(e,root,path,&leaf);if(p<0) return -1;
    int fd=openat(p,leaf,O_RDONLY|O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW);int saved=errno;free(leaf);close(p);errno=saved;
    if(fd<0) error(e,"open directory");return fd;
}
static jlongArray attributes(JNIEnv *e,const struct stat *s) {
    int64_t sec=(int64_t)s->st_mtim.tv_sec,nsec=(int64_t)s->st_mtim.tv_nsec;
    if(sec>INT64_MAX/1000000000 || sec<INT64_MIN/1000000000 || nsec<0 || nsec>=1000000000) {
        errno=EOVERFLOW;error(e,"unsupported file timestamp");return NULL;
    }
    int64_t modified=sec*1000000000;
    if(modified>INT64_MAX-nsec) { errno=EOVERFLOW;error(e,"unsupported file timestamp");return NULL; }
    jlong values[]={s->st_dev,s->st_ino,s->st_nlink,s->st_mode,s->st_size,modified+nsec};
    jlongArray a=(*e)->NewLongArray(e,6);if(a) (*e)->SetLongArrayRegion(e,a,0,6,values);return a;
}
JNIEXPORT jlong JNICALL JNI(root)(JNIEnv *e,jobject self,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return -1;
    /* Android grants apps search, not directory-listing permission on /data ancestors.
     * O_PATH pins those ancestors without requiring read permission or following links.
     * Only the final owned private root is opened readably for inventory/fsync. Relative
     * mutation parents below it retain their existing readable/fsync-able descriptors. */
    int root=open("/",O_PATH|O_CLOEXEC|O_DIRECTORY);int fd=-1;
    if(root<0) error(e,"root anchor");
    else if(*s=='/' && s[1]) {
        char *leaf=NULL;int p=parent_mode(e,root,s+1,&leaf,O_PATH);
        if(p>=0) {
            fd=openat(p,leaf,O_RDONLY|O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW);
            int saved=errno;free(leaf);close(p);errno=saved;
            if(fd<0) error(e,"open private root");
        }
    } else { errno=EINVAL;error(e,"absolute root"); }
    if(root>=0) close(root);(*e)->ReleaseStringUTFChars(e,path,s);return fd;
}
JNIEXPORT void JNICALL JNI(close)(JNIEnv *e,jobject self,jlong fd) { (void)self;if(close((int)fd)<0) error(e,"close"); }
JNIEXPORT jlongArray JNICALL JNI(fstat)(JNIEnv *e,jobject self,jlong fd) {
    (void)self;struct stat s;if(fstat((int)fd,&s)<0) { error(e,"fstat");return NULL; }return attributes(e,&s);
}
JNIEXPORT jlongArray JNICALL JNI(stat)(JNIEnv *e,jobject self,jlong root,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return NULL;
    char *leaf=NULL;int p=parent(e,(int)root,s,&leaf);struct stat st;int result=-1,saved=errno;
    if(p>=0) { result=fstatat(p,leaf,&st,AT_SYMLINK_NOFOLLOW);saved=errno;free(leaf);close(p); }
    (*e)->ReleaseStringUTFChars(e,path,s);
    if(p<0) { if(saved==ENOENT) (*e)->ExceptionClear(e);return NULL; }
    if(result<0) { if(saved!=ENOENT) { errno=saved;error(e,"stat"); }return NULL; }return attributes(e,&st);
}
JNIEXPORT jobjectArray JNICALL JNI(names)(JNIEnv *e,jobject self,jlong root,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return NULL;
    int fd=directory(e,(int)root,s);(*e)->ReleaseStringUTFChars(e,path,s);if(fd<0) return NULL;
    DIR *d=fdopendir(fd);if(!d) { close(fd);error(e,"fdopendir");return NULL; }
    char **names=NULL;size_t n=0;struct dirent *entry;int bad=0;errno=0;
    while((entry=readdir(d))) {
        if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,"..")) continue;
        if(n>=100000) { errno=EINVAL;bad=1;break; }
        char **next=realloc(names,(n+1)*sizeof *names);if(!next) { errno=ENOMEM;bad=1;break; }names=next;
        /* Unknown/non-ASCII filesystem bytes must block that item, not hide all safe siblings.
         * This non-addressable ASCII marker cannot collide with a safe path component and is
         * never passed to an operation: parent() rejects '!'. No raw bytes enter NewStringUTF. */
        char marker[80];snprintf(marker,sizeof marker,"!unsafe-%llu-%zu",(unsigned long long)entry->d_ino,n);
        names[n]=strdup(safe(entry->d_name)?entry->d_name:marker);if(!names[n]) { bad=1;break; }n++;
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
JNIEXPORT jlong JNICALL JNI(open)(JNIEnv *e,jobject self,jlong root,jstring path,jlong links) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return -1;
    char *leaf=NULL;int p=parent(e,(int)root,s,&leaf);int fd=-1;
    if(p>=0) { fd=openat(p,leaf,O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);struct stat st;
        if(fd>=0 && (fstat(fd,&st)<0 || !S_ISREG(st.st_mode) || links<1 || (jlong)st.st_nlink!=links)) { close(fd);fd=-1;errno=EINVAL; }
        if(fd<0) error(e,"open regular file with expected links");free(leaf);close(p); }
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
    (void)self;const char *s=(*e)->GetStringUTFChars(e,source,NULL);if(!s) return;
    const char *t=(*e)->GetStringUTFChars(e,target,NULL);if(!t) { (*e)->ReleaseStringUTFChars(e,source,s);return; }
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
JNIEXPORT void JNICALL JNI(remove)(JNIEnv *e,jobject self,jlong root,jstring path,jboolean directoryFlag,jlong device,jlong inode,jlong links) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return;
    char *leaf=NULL;int p=parent(e,(int)root,s,&leaf);
    if(p>=0) { struct stat st;
        if(fstatat(p,leaf,&st,AT_SYMLINK_NOFOLLOW)<0) error(e,"unlink stat");
        else if((jlong)st.st_dev!=device || (jlong)st.st_ino!=inode || (directoryFlag && !S_ISDIR(st.st_mode)) || (!directoryFlag && (!S_ISREG(st.st_mode)||links<1||(jlong)st.st_nlink!=links))) { errno=EINVAL;error(e,"unlink identity/kind/link"); }
        else if(unlinkat(p,leaf,directoryFlag?AT_REMOVEDIR:0)<0) error(e,"unlinkat");else if(fsync(p)<0) error(e,"unlink parent fsync");
        free(leaf);close(p); }
    (*e)->ReleaseStringUTFChars(e,path,s);
}
JNIEXPORT void JNICALL JNI(sync)(JNIEnv *e,jobject self,jlong root,jstring path) {
    (void)self;const char *s=(*e)->GetStringUTFChars(e,path,NULL);if(!s) return;
    int fd=directory(e,(int)root,s);if(fd>=0) { if(fsync(fd)<0) error(e,"directory fsync");close(fd); }(*e)->ReleaseStringUTFChars(e,path,s);
}
