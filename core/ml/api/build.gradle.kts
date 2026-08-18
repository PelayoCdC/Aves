plugins {
    id("aves.android.library")
}

android {
    namespace = "dev.pelayo.aves.core.ml.api"
}

// Este modulo define QUE hace un clasificador, nunca COMO.
// Regla que sostiene toda la estrategia de licencias del proyecto:
// aqui NO entra LiteRT, ni TensorFlow, ni ningun modelo. Si alguna vez hace
// falta anadir una de esas dependencias, es senal de que la abstraccion se
// esta rompiendo.
dependencies {
    api(projects.core.model)
}
