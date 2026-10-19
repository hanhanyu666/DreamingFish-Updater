function Test-MavenTransportFailure([string]$Text) {
    # Never retry an assertion, compiler error or application HTTP failure.
    return ($Text -match 'PluginResolutionException|DependencyResolutionException') -and
        ($Text -match 'Could not transfer artifact') -and
        ($Text -match 'Connection reset|Read timed out|Connect timed out|Connection timed out') -and
        ($Text -notmatch 'MojoFailureException|Compilation failure|Failures: [1-9]|Errors: [1-9]')
}
