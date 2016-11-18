# FIXME: tutum/debian will be deprecated soon: https://github.com/tutumcloud/tutum-debian/blob/master/README.md
FROM tutum/debian:jessie

RUN rm /etc/apt/apt.conf.d/docker-clean && apt-get update && echo "deb http://ftp.de.debian.org/debian jessie-backports main" >> /etc/apt/sources.list && apt-get update && apt-get install -y openjdk-8-jdk sudo net-tools wget sysvinit-core sysvinit sysvinit-utils curl vim man faketime unzip iptables iputils-ping logrotate && apt-get remove -y --purge --auto-remove systemd
