#! /bin/sh

##启动redis
echo "启动redis..."
REDIS_LOG_PATH=$BK_REPO_REDIS_PATH/log
REDIS_DATA_PATH=$BK_REPO_REDIS_PATH/data
mkdir -p $REDIS_LOG_PATH
mkdir -p $REDIS_DATA_PATH
redis-server --daemonize yes --logfile $REDIS_LOG_PATH/redis.log --dir $REDIS_DATA_PATH --appendonly yes

##启动mongodb
echo "启动mongodb..."
MONGO_DATA_PATH=$BK_REPO_MONGO_PATH/lib/mongo
MONGO_LOG_PATH=$BK_REPO_MONGO_PATH/log/mongodb
mkdir -p $MONGO_DATA_PATH
mkdir -p $MONGO_LOG_PATH
mongod --dbpath $MONGO_DATA_PATH --logpath $MONGO_LOG_PATH/mongod.log --fork

DEFAULT_ADMIN_PWD="password"
DEFAULT_ADMIN_PWD_MD5="5f4dcc3b5aa765d61d8327deb882cf99"
DEFAULT_PLATFORM_AK="18b61c9c-901b-4ea3-89c3-1f74be944b66"
DEFAULT_PLATFORM_SK="Us8ZGDXPqk86cwMukYABQqCZLAkM3K"
CRED_FILE="$BK_REPO_MONGO_PATH/bkrepo-bootstrap.env"
INIT_JS="$BK_REPO_HOME/support-files/sql/init-data.js"
MONGO_URI="mongodb://127.0.0.1:27017/bkrepo"

rand_alnum() {
    dd if=/dev/urandom bs=64 count=1 2>/dev/null | tr -dc 'A-Za-z0-9' | head -c "$1"
}

is_public_default_cred() {
    [ "$BK_REPO_PASSWORD" = "$DEFAULT_ADMIN_PWD" ] || \
    [ "$BK_REPO_ACCESSKEY" = "$DEFAULT_PLATFORM_AK" ] || \
    [ "$BK_REPO_SECRETKEY" = "$DEFAULT_PLATFORM_SK" ]
}

write_init_js() {
    password_md5=$(printf '%s' "$BK_REPO_PASSWORD" | md5sum | awk '{print $1}')
    INIT_JS=$(mktemp)
    cp "$BK_REPO_HOME/support-files/sql/init-data.js" "$INIT_JS"
    sed -i "s/$DEFAULT_ADMIN_PWD_MD5/$password_md5/g" "$INIT_JS"
    sed -i "s/$DEFAULT_PLATFORM_AK/$BK_REPO_ACCESSKEY/g" "$INIT_JS"
    sed -i "s/$DEFAULT_PLATFORM_SK/$BK_REPO_SECRETKEY/g" "$INIT_JS"
}

echo "等待 mongodb..."
mongo_ready=0
i=0
while [ "$i" -lt 30 ]; do
    if mongo --quiet "$MONGO_URI" --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1; then
        mongo_ready=1
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "$mongo_ready" != "1" ]; then
    echo "mongodb 未就绪" >&2
    exit 1
fi

user_count=$(mongo --quiet "$MONGO_URI" --eval 'db.getCollection("user").count()' 2>/dev/null | tail -1 | tr -d '[:space:]')

##初始化mongodb
echo "初始化mongodb..."
if [ -f "$CRED_FILE" ]; then
    # 新装重启：沿用已生成的凭证，不轮换
    # shellcheck disable=SC1090
    . "$CRED_FILE"
    export BK_REPO_PASSWORD BK_REPO_ACCESSKEY BK_REPO_SECRETKEY BK_REPO_AUTHORIZATION
    write_init_js
elif [ "$user_count" = "0" ]; then
    # 空库：生成或使用环境变量，禁止公开默认值
    if [ -z "$BK_REPO_PASSWORD" ] || [ -z "$BK_REPO_ACCESSKEY" ] || [ -z "$BK_REPO_SECRETKEY" ]; then
        echo "生成 all-in-one 初始凭证..."
        BK_REPO_PASSWORD=$(rand_alnum 16)
        if [ -r /proc/sys/kernel/random/uuid ]; then
            BK_REPO_ACCESSKEY=$(cat /proc/sys/kernel/random/uuid)
        else
            BK_REPO_ACCESSKEY=$(rand_alnum 32)
        fi
        BK_REPO_SECRETKEY=$(rand_alnum 32)
    fi
    if [ -z "$BK_REPO_PASSWORD" ] || [ -z "$BK_REPO_ACCESSKEY" ] || [ -z "$BK_REPO_SECRETKEY" ] || is_public_default_cred; then
        echo "BK_REPO_PASSWORD / BK_REPO_ACCESSKEY / BK_REPO_SECRETKEY 必须设置为非公开默认值" >&2
        exit 1
    fi
    BK_REPO_AUTHORIZATION="Platform $(printf '%s:%s' "$BK_REPO_ACCESSKEY" "$BK_REPO_SECRETKEY" | base64 | tr -d '\n')"
    export BK_REPO_PASSWORD BK_REPO_ACCESSKEY BK_REPO_SECRETKEY BK_REPO_AUTHORIZATION
    umask 077
    cat > "$CRED_FILE" <<EOF
BK_REPO_PASSWORD=$BK_REPO_PASSWORD
BK_REPO_ACCESSKEY=$BK_REPO_ACCESSKEY
BK_REPO_SECRETKEY=$BK_REPO_SECRETKEY
BK_REPO_AUTHORIZATION=$BK_REPO_AUTHORIZATION
EOF
    echo "初始凭证已写入 $CRED_FILE （请从该文件读取 admin 口令与平台 AK/SK）"
    write_init_js
fi

mongo "$MONGO_URI" "$INIT_JS"
if [ "$INIT_JS" != "$BK_REPO_HOME/support-files/sql/init-data.js" ]; then
    rm -f "$INIT_JS"
fi

mkdir -p $BK_REPO_LOGS_DIR/nginx
chmod 777 $BK_REPO_LOGS_DIR/nginx

mkdir -p $BK_REPO_LOGS_DIR/bkrepo
chmod 777 $BK_REPO_LOGS_DIR/bkrepo

##初始化网关配置
echo "渲染网关配置..."
touch repo.env
$BK_REPO_HOME/scripts/render_tpl -u -p $BK_REPO_HOME -m . -e repo.env $BK_REPO_HOME/support-files/templates/gateway#vhosts#bkrepo.server.conf
$BK_REPO_HOME/scripts/render_tpl -u -p $BK_REPO_HOME -m . -e repo.env $BK_REPO_HOME/support-files/templates/gateway#vhosts#bkrepo.docker.server.conf
$BK_REPO_HOME/scripts/render_tpl -u -p $BK_REPO_HOME -m . -e repo.env $BK_REPO_HOME/support-files/templates/gateway#server.common.conf
$BK_REPO_HOME/scripts/render_tpl -u -p $BK_REPO_HOME -m . -e repo.env $BK_REPO_HOME/support-files/templates/gateway#lua#init.lua
$BK_REPO_HOME/scripts/render_tpl -u -p $BK_REPO_HOME -m . -e repo.env -E BK_REPO_SHOW_ANALYST_MENU=true $BK_REPO_HOME/support-files/templates/frontend#ui#index.html

##启动网关程序
echo "启动网关..."
rm -rf /usr/local/openresty/nginx/conf
ln -s $BK_REPO_HOME/gateway /usr/local/openresty/nginx/conf
mkdir -p /usr/local/openresty/nginx/run/
cd /usr/local/openresty/nginx
/usr/local/openresty/nginx/sbin/nginx

##启动assembly程序
echo "启动boot-assembly..."
cd $BK_REPO_HOME/backend/assembly/
source $BK_REPO_HOME/backend/assembly/service.env
java -server \
     -Dsun.jnu.encoding=UTF-8 \
     -Dfile.encoding=UTF-8 \
     -Xlog:gc*:file=$BK_REPO_LOGS_DIR/bkrepo/gc.log:time,level,tags \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=oom.hprof \
     -XX:ErrorFile=$BK_REPO_LOGS_DIR/bkrepo/error_sys.log \
     -Dspring.profiles.active=$BK_REPO_PROFILE \
     -Doci.domain=$BK_REPO_DOCKER_HOST \
     -Doci.authUrl=http://$BK_REPO_DOCKER_HOST/v2/auth \
     -Dhelm.domain=http://$BK_REPO_HOST/helm \
     -Dlogging.path=$BK_REPO_LOGS_DIR/bkrepo \
     -Dstorage.filesystem.path=$BK_REPO_DATA_PATH \
     $BK_REPO_JVM_OPTION \
     $MAIN_CLASS