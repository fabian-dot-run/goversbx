# The kit's payload, under a prefix no other kit names. No base image, so the build needs no pull;
# --chmod makes the payload readable for the agent user whatever the checkout's umask was.
FROM scratch
COPY --chmod=0755 stage/ /
