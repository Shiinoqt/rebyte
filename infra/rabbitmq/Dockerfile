# RabbitMQ + plugin delayed-message.
#
# Il webhook di pagamento simulato (docs/architecture.md §3) pubblica la
# conferma con un ritardo, e §5 lo mette nella topologia. Il plugin non è
# incluso nell'immagine ufficiale, quindi va aggiunto qui.
#
# PLUGIN_VERSION deve restare allineata alla minor della base image:
# la release v4.1.0 del plugin è compilata contro RabbitMQ 4.1.0.
# Il .ez è bytecode Erlang, indipendente dall'architettura (amd64 e arm64).

FROM rabbitmq:4.1-management

ARG PLUGIN_VERSION=4.1.0

ADD --chmod=644 \
  https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v${PLUGIN_VERSION}/rabbitmq_delayed_message_exchange-${PLUGIN_VERSION}.ez \
  /opt/rabbitmq/plugins/

RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
