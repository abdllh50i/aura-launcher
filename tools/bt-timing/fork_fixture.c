/* Emulator-only fixture: two processes executing the same bind-mounted ELF. */
#include <sys/prctl.h>
#include <unistd.h>
#include <signal.h>
int main(void) {
    prctl(PR_SET_NAME, "amri_goc_test", 0, 0, 0);
    if (fork() == 0) prctl(PR_SET_PDEATHSIG, SIGTERM);
    for (;;) pause();
}
